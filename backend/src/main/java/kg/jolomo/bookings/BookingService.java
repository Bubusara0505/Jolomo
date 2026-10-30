package kg.jolomo.bookings;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore;
import kg.jolomo.events.Json;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.IdempotencyService;
import kg.jolomo.trips.TripCancellationListener;
import kg.jolomo.verification.EligibilityService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Брони мест (SP06). Запрос места ничего не занимает: места занимает только принятие водителем. Принятие, отказ и
 * отмена идут под блокировкой строки поездки (SELECT ... FOR UPDATE), затем строки брони, всегда в этом порядке, так
 * что у последнего места ровно один победитель, а отмена освобождает места не более одного раза (освобождает только
 * переход ACCEPTED -> CANCELLED, а он происходит один раз под блокировкой). Контактов в ответах нет: их откроет
 * SP07-01 после ACCEPTED. Чужую бронь не отличаем от несуществующей.
 */
@Service
public class BookingService implements TripCancellationListener {

    /** Бронь без телефонов и документов. priceKgs это цена одного места на момент запроса. */
    public record Booking(UUID id, UUID tripId, UUID passengerId, int seats, BookingStatus status, int priceKgs,
                          int totalKgs, String currency, Instant createdAt, Instant updatedAt) {}

    private record TripRow(UUID ownerId, String status, Instant departureFrom, int seatsAvailable, int priceKgs) {}

    private record Locked(TripRow trip, Booking booking) {}

    private record IdStatus(UUID id, String status) {}

    private static final String AGGREGATE = "BOOKING";
    static final String SCOPE_REQUEST = "BOOKING_REQUEST";

    private static final String SELECT_BOOKING = """
            SELECT id, trip_id, passenger_user_id, seats, status, price_kgs, created_at, updated_at FROM bookings""";

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final DomainEventStore events;
    private final EligibilityService eligibility;
    private final IdempotencyService idempotency;

    public BookingService(JdbcClient jdbc, TransactionOperations tx, Clock clock, DomainEventStore events,
                          EligibilityService eligibility, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
        this.eligibility = eligibility;
        this.idempotency = idempotency;
    }

    // ---------- запрос места ----------

    /**
     * Запрос места с Idempotency-Key (SP06-03). Бронь и запись идемпотентности фиксируются одной транзакцией.
     * Повтор с тем же ключом и тем же телом вернёт сохранённый ответ, с другим телом это 409.
     */
    public IdempotencyService.Result requestWithKey(UUID passengerId, UUID tripId, int seats, String key) {
        String validKey = IdempotencyService.requireValidKey(key);
        String fingerprint = IdempotencyService.fingerprint(SCOPE_REQUEST, String.valueOf(tripId), String.valueOf(seats));
        return idempotency.execute(passengerId, SCOPE_REQUEST, validKey, fingerprint, () -> {
            Booking booking = request(passengerId, tripId, seats);
            return new IdempotencyService.Outcome(201, toJson(booking));
        });
    }

    /** Запрос места (SP06-01): поездка существует и опубликована, не своя, не отправилась, мест хватает. */
    public Booking request(UUID passengerId, UUID tripId, int seats) {
        if (seats <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEATS", "error.booking.invalid_seats");
        }
        return tx.execute(status -> {
            String userStatus = jdbc.sql("SELECT status FROM users WHERE id = :id")
                    .param("id", passengerId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (!"ACTIVE".equals(userStatus)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE", "error.booking.user_not_active");
            }
            // FOR SHARE: пока водитель отменяет поездку (FOR UPDATE), новый запрос ждёт и увидит отмену, а не проскочит мимо.
            Optional<TripRow> found = jdbc.sql("""
                            SELECT driver_user_id, status, departure_from, seats_available, price_kgs
                            FROM trips WHERE id = :id FOR SHARE
                            """)
                    .param("id", tripId)
                    .query((rs, i) -> tripRow(rs))
                    .optional();
            if (found.isEmpty() || !"PUBLISHED".equals(found.get().status())) {
                throw tripNotFound();
            }
            TripRow trip = found.get();
            if (trip.ownerId().equals(passengerId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "OWN_TRIP", "error.booking.own_trip");
            }
            if (!trip.departureFrom().isAfter(clock.instant())) {
                throw new ApiException(HttpStatus.CONFLICT, "TRIP_DEPARTED", "error.booking.trip_departed");
            }
            if (seats > trip.seatsAvailable()) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_ENOUGH_SEATS", "error.booking.not_enough_seats");
            }
            Boolean active = jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM bookings WHERE trip_id = :t AND passenger_user_id = :p
                                           AND status IN ('REQUESTED', 'ACCEPTED'))
                            """)
                    .param("t", tripId)
                    .param("p", passengerId)
                    .query(Boolean.class)
                    .single();
            if (active) {
                throw alreadyActive();
            }
            UUID id = UUID.randomUUID();
            OffsetDateTime now = utc(clock.instant());
            try {
                jdbc.sql("""
                                INSERT INTO bookings (id, trip_id, passenger_user_id, seats, price_kgs, status, created_at, updated_at)
                                VALUES (:id, :t, :p, :seats, :price, 'REQUESTED', :now, :now)
                                """)
                        .param("id", id)
                        .param("t", tripId)
                        .param("p", passengerId)
                        .param("seats", seats)
                        .param("price", trip.priceKgs())
                        .param("now", now)
                        .update();
            } catch (DuplicateKeyException race) {
                // Гонка двух запросов одного пассажира: индекс базы пропустил только один.
                throw alreadyActive();
            }
            Booking booking = find(id).orElseThrow();
            emit("BOOKING_REQUESTED", booking, passengerId, null, "REQUESTED", null);
            return booking;
        });
    }

    // ---------- решения водителя и отмена ----------

    /**
     * Водитель принимает запрос. Под блокировкой поездки проверяется, что мест хватает, и места списываются. Повтор
     * принятия уже принятой брони безопасен и места второй раз не списывает.
     */
    public Booking accept(UUID driverId, UUID bookingId) {
        return tx.execute(status -> {
            Locked l = lock(bookingId);
            if (!l.trip().ownerId().equals(driverId)) {
                throw bookingNotFound();
            }
            Booking b = l.booking();
            if (b.status() == BookingStatus.ACCEPTED) {
                return b;
            }
            if (b.status() != BookingStatus.REQUESTED) {
                throw invalidState();
            }
            EligibilityService.Decision decision = eligibility.canManageBookings(driverId);
            if (!decision.allowed()) {
                throw new ApiException(HttpStatus.FORBIDDEN, decision.reason().name(),
                        "error.booking." + decision.reason().name().toLowerCase());
            }
            if (!"PUBLISHED".equals(l.trip().status())) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRIP_STATE", "error.trip.invalidState");
            }
            if (!l.trip().departureFrom().isAfter(clock.instant())) {
                throw tripDeparted();
            }
            if (l.trip().seatsAvailable() < b.seats()) {
                throw new ApiException(HttpStatus.CONFLICT, "NO_SEATS_LEFT", "error.booking.no_seats_left");
            }
            jdbc.sql("UPDATE trips SET seats_available = seats_available - :n, updated_at = :now WHERE id = :id")
                    .param("n", b.seats())
                    .param("now", utc(clock.instant()))
                    .param("id", b.tripId())
                    .update();
            setStatus(bookingId, BookingStatus.ACCEPTED, null, true);
            Booking after = find(bookingId).orElseThrow();
            emit("BOOKING_ACCEPTED", after, driverId, "REQUESTED", "ACCEPTED", null);
            return after;
        });
    }

    /** Водитель отклоняет запрос. Места не меняются. Повтор безопасен. */
    public Booking reject(UUID driverId, UUID bookingId) {
        return tx.execute(status -> {
            Locked l = lock(bookingId);
            if (!l.trip().ownerId().equals(driverId)) {
                throw bookingNotFound();
            }
            Booking b = l.booking();
            if (b.status() == BookingStatus.REJECTED) {
                return b;
            }
            if (b.status() != BookingStatus.REQUESTED) {
                throw invalidState();
            }
            setStatus(bookingId, BookingStatus.REJECTED, null, true);
            Booking after = find(bookingId).orElseThrow();
            emit("BOOKING_REJECTED", after, driverId, "REQUESTED", "REJECTED", null);
            return after;
        });
    }

    /**
     * Отмена. Пассажир отменяет свой запрос или принятую бронь, водитель только принятую (непринятый запрос он
     * отклоняет). После отправления отмены нет. Места возвращаются только при переходе ACCEPTED -> CANCELLED,
     * то есть не более одного раза; повторная отмена ничего не меняет.
     */
    public Booking cancel(UUID actorId, UUID bookingId) {
        return tx.execute(status -> {
            Locked l = lock(bookingId);
            Booking b = l.booking();
            boolean passenger = b.passengerId().equals(actorId);
            boolean driver = l.trip().ownerId().equals(actorId);
            if (!passenger && !driver) {
                throw bookingNotFound();
            }
            if (b.status() == BookingStatus.CANCELLED) {
                return b;
            }
            boolean allowed = passenger
                    ? b.status() == BookingStatus.REQUESTED || b.status() == BookingStatus.ACCEPTED
                    : b.status() == BookingStatus.ACCEPTED;
            if (!allowed) {
                throw invalidState();
            }
            if (!l.trip().departureFrom().isAfter(clock.instant())) {
                throw tripDeparted();
            }
            if (b.status() == BookingStatus.ACCEPTED) {
                jdbc.sql("UPDATE trips SET seats_available = seats_available + :n, updated_at = :now WHERE id = :id")
                        .param("n", b.seats())
                        .param("now", utc(clock.instant()))
                        .param("id", b.tripId())
                        .update();
            }
            String by = passenger ? "PASSENGER" : "DRIVER";
            setStatus(bookingId, BookingStatus.CANCELLED, by, false);
            Booking after = find(bookingId).orElseThrow();
            emit("BOOKING_CANCELLED", after, actorId, b.status().name(), "CANCELLED", by);
            return after;
        });
    }

    /**
     * Водитель фиксирует, что пассажир поехал (SP07-03). Только водитель этой поездки, только из ACCEPTED и только
     * когда время отправления наступило: по часам бронь сама не завершается, и пассажир завершить её не может.
     * Места не возвращаются (поездка уже отправилась). Допуск водителя не проверяется: приостановленный водитель
     * всё равно может записать факт. Повтор безопасен.
     */
    public Booking complete(UUID driverId, UUID bookingId) {
        return finish(driverId, bookingId, BookingStatus.COMPLETED, "BOOKING_COMPLETED");
    }

    /** Водитель фиксирует неявку пассажира (отдельное состояние NO_SHOW). Правила те же, что у complete. */
    public Booking markNoShow(UUID driverId, UUID bookingId) {
        return finish(driverId, bookingId, BookingStatus.NO_SHOW, "BOOKING_NO_SHOW");
    }

    private Booking finish(UUID driverId, UUID bookingId, BookingStatus target, String eventType) {
        return tx.execute(status -> {
            Locked l = lock(bookingId);
            if (!l.trip().ownerId().equals(driverId)) {
                throw bookingNotFound();
            }
            Booking b = l.booking();
            if (b.status() == target) {
                return b;
            }
            if (b.status() != BookingStatus.ACCEPTED) {
                throw invalidState();
            }
            if (l.trip().departureFrom().isAfter(clock.instant())) {
                throw new ApiException(HttpStatus.CONFLICT, "TRIP_NOT_DEPARTED", "error.booking.trip_not_departed");
            }
            setStatus(bookingId, target, null, false);
            Booking after = find(bookingId).orElseThrow();
            emit(eventType, after, driverId, "ACCEPTED", target.name(), null);
            return after;
        });
    }

    /** Водитель отменил поездку: все активные брони снимаются (места не возвращаем, поездки больше нет). */
    @Override
    public void onTripCancelled(UUID tripId, UUID actorUserId) {
        tx.executeWithoutResult(status -> {
            List<IdStatus> active = jdbc.sql("""
                            SELECT id, status FROM bookings
                            WHERE trip_id = :t AND status IN ('REQUESTED', 'ACCEPTED') ORDER BY id FOR UPDATE
                            """)
                    .param("t", tripId)
                    .query((rs, i) -> new IdStatus(rs.getObject(1, UUID.class), rs.getString(2)))
                    .list();
            for (IdStatus row : active) {
                setStatus(row.id(), BookingStatus.CANCELLED, "TRIP_CANCELLED", false);
                Booking after = find(row.id()).orElseThrow();
                emit("BOOKING_CANCELLED", after, actorUserId, row.status(), "CANCELLED", "TRIP_CANCELLED");
            }
        });
    }

    // ---------- чтение ----------

    /** Свои запросы, новые первыми. */
    public List<Booking> listMine(UUID passengerId) {
        return jdbc.sql(SELECT_BOOKING + " WHERE passenger_user_id = :p ORDER BY created_at DESC, id")
                .param("p", passengerId)
                .query((rs, i) -> booking(rs))
                .list();
    }

    /** Запросы на свою поездку (водителю). Чужая поездка это 404. */
    public List<Booking> listForTrip(UUID driverId, UUID tripId) {
        Optional<UUID> owner = jdbc.sql("SELECT driver_user_id FROM trips WHERE id = :id")
                .param("id", tripId)
                .query(UUID.class)
                .optional();
        if (owner.isEmpty() || !owner.get().equals(driverId)) {
            throw tripNotFound();
        }
        return jdbc.sql(SELECT_BOOKING + " WHERE trip_id = :t ORDER BY created_at, id")
                .param("t", tripId)
                .query((rs, i) -> booking(rs))
                .list();
    }

    /** Бронь видят только её пассажир и водитель этой поездки. */
    public Optional<Booking> findVisible(UUID userId, UUID bookingId) {
        return jdbc.sql(SELECT_BOOKING + " WHERE id = :id AND (passenger_user_id = :u"
                        + " OR trip_id IN (SELECT id FROM trips WHERE driver_user_id = :u))")
                .param("id", bookingId)
                .param("u", userId)
                .query((rs, i) -> booking(rs))
                .optional();
    }

    // ---------- внутреннее ----------

    Optional<Booking> find(UUID id) {
        return jdbc.sql(SELECT_BOOKING + " WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> booking(rs))
                .optional();
    }

    /** Блокирует поездку, затем бронь: единый порядок для accept, reject, cancel и отмены поездки. */
    private Locked lock(UUID bookingId) {
        UUID tripId = jdbc.sql("SELECT trip_id FROM bookings WHERE id = :id")
                .param("id", bookingId)
                .query(UUID.class)
                .optional()
                .orElseThrow(BookingService::bookingNotFound);
        TripRow trip = jdbc.sql("""
                        SELECT driver_user_id, status, departure_from, seats_available, price_kgs
                        FROM trips WHERE id = :id FOR UPDATE
                        """)
                .param("id", tripId)
                .query((rs, i) -> tripRow(rs))
                .single();
        jdbc.sql("SELECT id FROM bookings WHERE id = :id FOR UPDATE")
                .param("id", bookingId)
                .query(UUID.class)
                .single();
        return new Locked(trip, find(bookingId).orElseThrow());
    }

    private void setStatus(UUID id, BookingStatus to, String cancelledBy, boolean decided) {
        OffsetDateTime now = utc(clock.instant());
        jdbc.sql("""
                        UPDATE bookings SET status = :s, cancelled_by = :c, updated_at = :now,
                               decided_at = CASE WHEN :decided THEN :now ELSE decided_at END
                        WHERE id = :id
                        """)
                .param("s", to.name())
                .param("c", cancelledBy)
                .param("decided", decided)
                .param("now", now)
                .param("id", id)
                .update();
    }

    private void emit(String type, Booking b, UUID actor, String from, String to, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", b.tripId());
        payload.put("passengerId", b.passengerId());
        payload.put("seats", b.seats());
        payload.put("from", from);
        payload.put("to", to);
        payload.put("reason", reason);
        events.append(AGGREGATE, b.id(), type, actor, payload);
    }

    /** Тело ответа, которое сохраняется для повтора. Поля те же, что у Booking в обычных ответах. */
    static String toJson(Booking b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("tripId", b.tripId());
        m.put("passengerId", b.passengerId());
        m.put("seats", b.seats());
        m.put("status", b.status());
        m.put("priceKgs", b.priceKgs());
        m.put("totalKgs", b.totalKgs());
        m.put("currency", b.currency());
        m.put("createdAt", b.createdAt());
        m.put("updatedAt", b.updatedAt());
        return Json.write(m);
    }

    private static TripRow tripRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TripRow(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getInt(4), rs.getInt(5));
    }

    private static Booking booking(java.sql.ResultSet rs) throws java.sql.SQLException {
        int seats = rs.getInt(4);
        int price = rs.getInt(6);
        return new Booking(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), seats,
                BookingStatus.valueOf(rs.getString(5)), price, seats * price, "KGS",
                rs.getObject(7, OffsetDateTime.class).toInstant(), rs.getObject(8, OffsetDateTime.class).toInstant());
    }

    private static ApiException tripNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "error.trip.notFound");
    }

    private static ApiException bookingNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "error.booking.not_found");
    }

    private static ApiException invalidState() {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_BOOKING_STATE", "error.booking.invalid_state");
    }

    private static ApiException tripDeparted() {
        return new ApiException(HttpStatus.CONFLICT, "TRIP_DEPARTED", "error.booking.trip_departed");
    }

    private static ApiException alreadyActive() {
        return new ApiException(HttpStatus.CONFLICT, "BOOKING_ALREADY_ACTIVE", "error.booking.already_active");
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
