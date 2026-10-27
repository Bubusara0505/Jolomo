package kg.jolomo.trips;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.verification.DriverProfileService;
import kg.jolomo.verification.EligibilityService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Поездка (SP04-04): черновик с проверкой всех обязательных полей и публикация только для допущенного водителя при
 * загруженном выключателе обязательств. HTTP-маршруты, правка и отмена появятся в SP05-01. Тут же правила, которые
 * нельзя выразить в базе (будущее время отправления, допуск); остальное держит схема (V10).
 */
@Service
public class TripService {

    public record TripDraft(
            Direction direction,
            UUID pickupLocationId,
            Instant departureFrom,
            Instant departureTo,
            int seatsTotal,
            int priceKgs,
            int parcelSlotsTotal) {}

    public record Trip(UUID id, UUID ownerId, TripStatus status, Direction direction, UUID pickupLocationId,
                       Instant departureFrom, Instant departureTo, int seatsTotal, int seatsAvailable, int priceKgs) {}

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final EligibilityService eligibility;
    private final DriverProfileService profiles;

    public TripService(JdbcClient jdbc, TransactionOperations tx, Clock clock, EligibilityService eligibility,
                       DriverProfileService profiles) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.eligibility = eligibility;
        this.profiles = profiles;
    }

    public Trip createDraft(UUID ownerId, TripDraft d) {
        if (d == null || d.direction() == null) {
            throw bad("INVALID_DIRECTION");
        }
        if (d.pickupLocationId() == null || d.departureFrom() == null || d.departureTo() == null) {
            throw bad("INVALID_REQUEST");
        }
        if (!d.departureTo().isAfter(d.departureFrom())) {
            throw bad("INVALID_WINDOW");
        }
        if (!d.departureFrom().isAfter(clock.instant())) {
            throw bad("DEPARTURE_IN_PAST");
        }
        if (d.seatsTotal() <= 0) {
            throw bad("INVALID_SEATS");
        }
        if (d.priceKgs() <= 0) {
            throw bad("INVALID_PRICE");
        }
        if (d.parcelSlotsTotal() < 0) {
            throw bad("INVALID_PARCEL_SLOTS");
        }
        if (profiles.find(ownerId).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_DRIVER", "error.trip.notDriver");
        }
        Optional<String> city = jdbc.sql("SELECT city FROM pickup_locations WHERE id = :id AND active")
                .param("id", d.pickupLocationId())
                .query(String.class)
                .optional();
        if (city.isEmpty()) {
            throw bad("PICKUP_NOT_IN_CATALOG");
        }
        if (!city.get().equals(d.direction().originCity())) {
            throw bad("PICKUP_WRONG_CITY");
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = utc(clock.instant());
        jdbc.sql("""
                        INSERT INTO trips
                            (id, driver_user_id, direction, pickup_location_id, departure_from, departure_to,
                             seats_total, seats_available, parcel_slots_total, parcel_slots_available,
                             price_kgs, status, created_at, updated_at)
                        VALUES (:id, :owner, :dir, :pickup, :from, :to, :seats, :seats, :parcels, :parcels,
                                :price, 'DRAFT', :now, :now)
                        """)
                .param("id", id)
                .param("owner", ownerId)
                .param("dir", d.direction().name())
                .param("pickup", d.pickupLocationId())
                .param("from", utc(d.departureFrom()))
                .param("to", utc(d.departureTo()))
                .param("seats", d.seatsTotal())
                .param("parcels", d.parcelSlotsTotal())
                .param("price", d.priceKgs())
                .param("now", now)
                .update();
        return find(id).orElseThrow();
    }

    /** Публикует черновик своего водителя. Отказ: нет допуска, не загружены обязательства, время уже прошло. */
    public Trip publish(UUID ownerId, UUID tripId) {
        return tx.execute(status -> {
            var row = jdbc.sql("SELECT driver_user_id, status, departure_from FROM trips WHERE id = :id FOR UPDATE")
                    .param("id", tripId)
                    .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, OffsetDateTime.class).toInstant()})
                    .optional();
            // Чужую поездку не отличаем от несуществующей.
            if (row.isEmpty() || !ownerId.equals(row.get()[0])) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "error.trip.notFound");
            }
            if (!TripStatus.DRAFT.name().equals(row.get()[1])) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE", "error.trip.invalidState");
            }
            EligibilityService.Decision decision = eligibility.canPublishTrip(ownerId);
            if (!decision.allowed()) {
                throw new ApiException(HttpStatus.FORBIDDEN, decision.reason().name(),
                        "error.publish." + decision.reason().name().toLowerCase());
            }
            if (!((Instant) row.get()[2]).isAfter(clock.instant())) {
                throw new ApiException(HttpStatus.CONFLICT, "DEPARTURE_IN_PAST", "error.trip.departurePast");
            }
            OffsetDateTime now = utc(clock.instant());
            jdbc.sql("UPDATE trips SET status = 'PUBLISHED', published_at = :now, updated_at = :now WHERE id = :id")
                    .param("now", now)
                    .param("id", tripId)
                    .update();
            return find(tripId).orElseThrow();
        });
    }

    public Optional<Trip> find(UUID tripId) {
        return jdbc.sql("""
                        SELECT id, driver_user_id, status, direction, pickup_location_id, departure_from, departure_to,
                               seats_total, seats_available, price_kgs
                        FROM trips WHERE id = :id
                        """)
                .param("id", tripId)
                .query((rs, i) -> new Trip(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), TripStatus.valueOf(rs.getString(3)),
                        Direction.valueOf(rs.getString(4)), rs.getObject(5, UUID.class),
                        rs.getObject(6, OffsetDateTime.class).toInstant(), rs.getObject(7, OffsetDateTime.class).toInstant(),
                        rs.getInt(8), rs.getInt(9), rs.getInt(10)))
                .optional();
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "error.trip." + code.toLowerCase());
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
