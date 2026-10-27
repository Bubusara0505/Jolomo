package kg.jolomo.trips;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import kg.jolomo.events.DomainEventStore;
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
                       Instant departureFrom, Instant departureTo, int seatsTotal, int seatsAvailable, int priceKgs,
                       int parcelSlotsTotal, int parcelSlotsAvailable) {}

    /** Частичная правка. Пустые поля не меняются. Статус меняется отдельными методами publish и cancel. */
    public record TripPatch(
            Direction direction,
            UUID pickupLocationId,
            Instant departureFrom,
            Instant departureTo,
            Integer seatsTotal,
            Integer priceKgs,
            Integer parcelSlotsTotal) {

        boolean isEmpty() {
            return direction == null && pickupLocationId == null && departureFrom == null && departureTo == null
                    && seatsTotal == null && priceKgs == null && parcelSlotsTotal == null;
        }

        boolean touchesSchedule() {
            return direction != null || pickupLocationId != null || departureFrom != null || departureTo != null;
        }
    }

    /** Публичный вид опубликованной поездки: без владельца, телефонов и документов. */
    public record PublicTrip(UUID id, Direction direction, Instant departureFrom, Instant departureTo,
                             String pickupCode, String pickupNameKy, String pickupNameRu, String pickupNameEn,
                             double pickupLatitude, double pickupLongitude, int seatsAvailable, int priceKgs,
                             String currency) {}

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final EligibilityService eligibility;
    private final DriverProfileService profiles;
    private final DomainEventStore events;
    private final java.util.List<TripCancellationListener> cancellationListeners;

    public TripService(JdbcClient jdbc, TransactionOperations tx, Clock clock, EligibilityService eligibility,
                       DriverProfileService profiles, DomainEventStore events) {
        this(jdbc, tx, clock, eligibility, profiles, events, java.util.List.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TripService(JdbcClient jdbc, TransactionOperations tx, Clock clock, EligibilityService eligibility,
                       DriverProfileService profiles, DomainEventStore events,
                       java.util.List<TripCancellationListener> cancellationListeners) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.eligibility = eligibility;
        this.profiles = profiles;
        this.events = events;
        this.cancellationListeners = cancellationListeners;
    }

    public Trip createDraft(UUID ownerId, TripDraft d) {
        validate(d);
        if (profiles.find(ownerId).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_DRIVER", "error.trip.notDriver");
        }
        UUID id = UUID.randomUUID();
        return tx.execute(txStatus -> {
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("direction", d.direction());
        payload.put("pickupLocationId", d.pickupLocationId());
        payload.put("departureFrom", d.departureFrom());
        payload.put("departureTo", d.departureTo());
        payload.put("seatsTotal", d.seatsTotal());
        payload.put("priceKgs", d.priceKgs());
        payload.put("parcelSlotsTotal", d.parcelSlotsTotal());
        payload.put("status", "DRAFT");
        emit("TRIP_CREATED", id, ownerId, payload);
        return find(id).orElseThrow();
        });
    }

    /** Все проверки полей поездки, общие для создания и правки черновика. */
    private void validate(TripDraft d) {
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
            emit("TRIP_PUBLISHED", tripId, ownerId, statusChange("DRAFT", "PUBLISHED"));
            return find(tripId).orElseThrow();
        });
    }

    /**
     * Правка своей поездки. Черновик правится целиком. У опубликованной можно менять только цену, число мест и слотов посылок
     * (маршрут и время зафиксированы: для их смены поездку отменяют и создают новую), и только пока водитель допущен.
     */
    public Trip update(UUID ownerId, UUID tripId, TripPatch p) {
        if (p == null || p.isEmpty()) {
            throw bad("INVALID_REQUEST");
        }
        return tx.execute(status -> {
            Row r = lockOwned(ownerId, tripId);
            switch (r.status()) {
                case DRAFT -> {
                    TripDraft merged = new TripDraft(
                            p.direction() != null ? p.direction() : r.direction(),
                            p.pickupLocationId() != null ? p.pickupLocationId() : r.pickupId(),
                            p.departureFrom() != null ? p.departureFrom() : r.from(),
                            p.departureTo() != null ? p.departureTo() : r.to(),
                            p.seatsTotal() != null ? p.seatsTotal() : r.seatsTotal(),
                            p.priceKgs() != null ? p.priceKgs() : r.price(),
                            p.parcelSlotsTotal() != null ? p.parcelSlotsTotal() : r.parcelsTotal());
                    validate(merged);
                    jdbc.sql("""
                                    UPDATE trips SET direction = :dir, pickup_location_id = :pickup, departure_from = :from,
                                        departure_to = :to, seats_total = :seats, seats_available = :seats,
                                        parcel_slots_total = :parcels, parcel_slots_available = :parcels,
                                        price_kgs = :price, updated_at = :now
                                    WHERE id = :id
                                    """)
                            .param("dir", merged.direction().name())
                            .param("pickup", merged.pickupLocationId())
                            .param("from", utc(merged.departureFrom()))
                            .param("to", utc(merged.departureTo()))
                            .param("seats", merged.seatsTotal())
                            .param("parcels", merged.parcelSlotsTotal())
                            .param("price", merged.priceKgs())
                            .param("now", utc(clock.instant()))
                            .param("id", tripId)
                            .update();
                }
                case PUBLISHED -> {
                    if (p.touchesSchedule()) {
                        throw new ApiException(HttpStatus.CONFLICT, "TRIP_FIELD_LOCKED", "error.trip.fieldLocked");
                    }
                    EligibilityService.Decision decision = eligibility.canPublishTrip(ownerId);
                    if (!decision.allowed()) {
                        throw new ApiException(HttpStatus.FORBIDDEN, decision.reason().name(),
                                "error.publish." + decision.reason().name().toLowerCase());
                    }
                    int seats = p.seatsTotal() != null ? p.seatsTotal() : r.seatsTotal();
                    int parcels = p.parcelSlotsTotal() != null ? p.parcelSlotsTotal() : r.parcelsTotal();
                    int price = p.priceKgs() != null ? p.priceKgs() : r.price();
                    if (seats <= 0) {
                        throw bad("INVALID_SEATS");
                    }
                    if (price <= 0) {
                        throw bad("INVALID_PRICE");
                    }
                    if (parcels < 0) {
                        throw bad("INVALID_PARCEL_SLOTS");
                    }
                    int seatsTaken = r.seatsTotal() - r.seatsAvailable();
                    int parcelsTaken = r.parcelsTotal() - r.parcelsAvailable();
                    if (seats < seatsTaken || parcels < parcelsTaken) {
                        throw new ApiException(HttpStatus.CONFLICT, "CAPACITY_BELOW_TAKEN", "error.trip.capacityBelowTaken");
                    }
                    jdbc.sql("""
                                    UPDATE trips SET seats_total = :seats, seats_available = :seats - :seatsTaken,
                                        parcel_slots_total = :parcels, parcel_slots_available = :parcels - :parcelsTaken,
                                        price_kgs = :price, updated_at = :now
                                    WHERE id = :id
                                    """)
                            .param("seats", seats)
                            .param("seatsTaken", seatsTaken)
                            .param("parcels", parcels)
                            .param("parcelsTaken", parcelsTaken)
                            .param("price", price)
                            .param("now", utc(clock.instant()))
                            .param("id", tripId)
                            .update();
                }
                default -> throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE", "error.trip.invalidState");
            }
            Trip after = find(tripId).orElseThrow();
            Map<String, Object> changes = diff(r, after);
            if (!changes.isEmpty()) {
                emit("TRIP_UPDATED", tripId, ownerId, Map.of("changes", changes));
            }
            return after;
        });
    }

    /**
     * Отмена своей поездки. Допустима и для недопущенного водителя (отменять можно всегда). Повторная отмена безопасна:
     * возвращает то же состояние, ничего не меняя. Завершённую отменить нельзя.
     */
    public Trip cancel(UUID ownerId, UUID tripId) {
        return tx.execute(status -> {
            Row r = lockOwned(ownerId, tripId);
            if (r.status() == TripStatus.COMPLETED) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE", "error.trip.invalidState");
            }
            if (r.status() != TripStatus.CANCELLED) {
                jdbc.sql("UPDATE trips SET status = 'CANCELLED', updated_at = :now WHERE id = :id")
                        .param("now", utc(clock.instant()))
                        .param("id", tripId)
                        .update();
                emit("TRIP_CANCELLED", tripId, ownerId, statusChange(r.status().name(), "CANCELLED"));
                // Активные брони снимаются в этой же транзакции: поездки нет, значит и мест нет.
                for (TripCancellationListener listener : cancellationListeners) {
                    listener.onTripCancelled(tripId, ownerId);
                }
            }
            return find(tripId).orElseThrow();
        });
    }

    /** Свои поездки, новые первыми. */
    public java.util.List<Trip> listOwn(UUID ownerId) {
        return jdbc.sql(SELECT_TRIP + " WHERE driver_user_id = :o ORDER BY departure_from DESC, id")
                .param("o", ownerId)
                .query((rs, i) -> trip(rs))
                .list();
    }

    /** Публичный вид: только опубликованная и ещё не отправившаяся поездка. */
    public Optional<PublicTrip> findPublic(UUID tripId) {
        return jdbc.sql("""
                        SELECT t.id, t.direction, t.departure_from, t.departure_to, p.code, p.name_ky, p.name_ru, p.name_en,
                               ST_Y(p.location::geometry), ST_X(p.location::geometry), t.seats_available, t.price_kgs, t.currency
                        FROM trips t JOIN pickup_locations p ON p.id = t.pickup_location_id
                        WHERE t.id = :id AND t.status = 'PUBLISHED' AND t.departure_to > :now
                        """)
                .param("id", tripId)
                .param("now", utc(clock.instant()))
                .query((rs, i) -> new PublicTrip(
                        rs.getObject(1, UUID.class), Direction.valueOf(rs.getString(2)),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getObject(4, OffsetDateTime.class).toInstant(),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getDouble(9), rs.getDouble(10), rs.getInt(11), rs.getInt(12), rs.getString(13)))
                .optional();
    }

    private record Row(TripStatus status, Direction direction, UUID pickupId, Instant from, Instant to,
                       int seatsTotal, int seatsAvailable, int parcelsTotal, int parcelsAvailable, int price) {}

    /** Блокирует строку поездки. Чужую поездку не отличаем от несуществующей. */
    private Row lockOwned(UUID ownerId, UUID tripId) {
        Optional<Object[]> owner = jdbc.sql("SELECT driver_user_id FROM trips WHERE id = :id FOR UPDATE")
                .param("id", tripId)
                .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class)})
                .optional();
        if (owner.isEmpty() || !ownerId.equals(owner.get()[0])) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "error.trip.notFound");
        }
        return jdbc.sql("""
                        SELECT status, direction, pickup_location_id, departure_from, departure_to, seats_total,
                               seats_available, parcel_slots_total, parcel_slots_available, price_kgs
                        FROM trips WHERE id = :id
                        """)
                .param("id", tripId)
                .query((rs, i) -> new Row(
                        TripStatus.valueOf(rs.getString(1)), Direction.valueOf(rs.getString(2)), rs.getObject(3, UUID.class),
                        rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getObject(5, OffsetDateTime.class).toInstant(),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10)))
                .single();
    }

    private static final String SELECT_TRIP = """
            SELECT id, driver_user_id, status, direction, pickup_location_id, departure_from, departure_to,
                   seats_total, seats_available, price_kgs, parcel_slots_total, parcel_slots_available
            FROM trips""";

    public Optional<Trip> find(UUID tripId) {
        return jdbc.sql(SELECT_TRIP + " WHERE id = :id")
                .param("id", tripId)
                .query((rs, i) -> trip(rs))
                .optional();
    }

    private static Trip trip(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Trip(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), TripStatus.valueOf(rs.getString(3)),
                Direction.valueOf(rs.getString(4)), rs.getObject(5, UUID.class),
                rs.getObject(6, OffsetDateTime.class).toInstant(), rs.getObject(7, OffsetDateTime.class).toInstant(),
                rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12));
    }

    private static final String AGGREGATE = "TRIP";

    private void emit(String type, UUID tripId, UUID actor, Map<String, Object> payload) {
        events.append(AGGREGATE, tripId, type, actor, payload);
    }

    private static Map<String, Object> statusChange(String from, String to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        return m;
    }

    /** Что изменилось в поездке: поле -> {from, to}. Пусто, если правка ничего не поменяла. */
    private static Map<String, Object> diff(Row before, Trip after) {
        Map<String, Object> m = new LinkedHashMap<>();
        change(m, "direction", before.direction(), after.direction());
        change(m, "pickupLocationId", before.pickupId(), after.pickupLocationId());
        change(m, "departureFrom", before.from(), after.departureFrom());
        change(m, "departureTo", before.to(), after.departureTo());
        change(m, "seatsTotal", before.seatsTotal(), after.seatsTotal());
        change(m, "parcelSlotsTotal", before.parcelsTotal(), after.parcelSlotsTotal());
        change(m, "priceKgs", before.price(), after.priceKgs());
        return m;
    }

    private static void change(Map<String, Object> m, String field, Object from, Object to) {
        if (!from.equals(to)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("from", from);
            c.put("to", to);
            m.put(field, c);
        }
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "error.trip." + code.toLowerCase());
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
