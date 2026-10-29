package kg.jolomo.parcels;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import kg.jolomo.events.DomainEventStore;
import kg.jolomo.events.Json;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.IdempotencyService;
import kg.jolomo.trips.TripCancellationListener;
import kg.jolomo.verification.ObligationConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Создание посылки (SP07-04, сценарий E). Default deny: пока на сервере не загружен набор обязательств для посылок
 * (obligation_config PARCEL_CREATE), создание отклонено, и клиент включить его не может. Проверяются поездка,
 * размер, категория (данные в parcel_categories) и форма телефона получателя. Создание ничего не занимает: слот
 * посылки закрепляет SP08-01. Цена посылки согласуется вне платформы и в модели её нет. Телефон получателя хранится
 * только в базе и не попадает ни в ответы, ни в события, ни в журнал.
 */
@Service
public class ParcelService implements TripCancellationListener {

    /** Заявка без телефонов: отправитель сам знает, кому отправил, а водителю телефон получателя пока не нужен. */
    public record Parcel(UUID id, UUID tripId, UUID senderId, String size, String category, ParcelStatus status,
                         Instant createdAt, Instant updatedAt) {}

    public record NewParcel(UUID tripId, String size, String category, String recipientPhone) {}

    private record TripRow(UUID ownerId, String status, Instant departureFrom, int parcelSlotsAvailable) {}

    private record IdStatus(UUID id, String status) {}

    static final String SCOPE_CREATE = "PARCEL_CREATE";
    private static final String AGGREGATE = "PARCEL";
    private static final Set<String> SIZES = Set.of("S", "M", "L");
    private static final Pattern PHONE = Pattern.compile("^\\+996\\d{9}$");
    private static final String SELECT_PARCEL = """
            SELECT id, trip_id, sender_user_id, size, category, status, created_at, updated_at FROM parcel_requests""";

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final DomainEventStore events;
    private final ObligationConfigService obligations;
    private final IdempotencyService idempotency;

    public ParcelService(JdbcClient jdbc, TransactionOperations tx, Clock clock, DomainEventStore events,
                         ObligationConfigService obligations, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
        this.obligations = obligations;
        this.idempotency = idempotency;
    }

    /** Создание с Idempotency-Key: заявка и запись ключа фиксируются одной транзакцией. */
    public IdempotencyService.Result createWithKey(UUID senderId, NewParcel cmd, String key) {
        String validKey = IdempotencyService.requireValidKey(key);
        String fingerprint = IdempotencyService.fingerprint(SCOPE_CREATE, String.valueOf(cmd.tripId()),
                String.valueOf(cmd.size()), String.valueOf(cmd.category()), String.valueOf(cmd.recipientPhone()));
        return idempotency.execute(senderId, SCOPE_CREATE, validKey, fingerprint,
                () -> new IdempotencyService.Outcome(201, toJson(create(senderId, cmd))));
    }

    public Parcel create(UUID senderId, NewParcel cmd) {
        return tx.execute(status -> {
            String userStatus = jdbc.sql("SELECT status FROM users WHERE id = :id")
                    .param("id", senderId)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (!"ACTIVE".equals(userStatus)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE", "error.parcel.user_not_active");
            }
            // Default deny: без загруженного набора обязательств любое создание отклонено, что бы ни прислал клиент.
            String obligationVersion = obligations.enabledVersion(ObligationConfigService.PARCEL_CREATE)
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "OBLIGATIONS_NOT_LOADED",
                            "error.parcel.obligations_not_loaded"));
            if (cmd.tripId() == null) {
                throw bad("INVALID_TRIP");
            }
            if (cmd.size() == null || !SIZES.contains(cmd.size())) {
                throw bad("INVALID_PARCEL_SIZE");
            }
            if (cmd.category() == null || !categoryActive(cmd.category())) {
                throw bad("INVALID_PARCEL_CATEGORY");
            }
            if (cmd.recipientPhone() == null || !PHONE.matcher(cmd.recipientPhone()).matches()) {
                throw bad("INVALID_RECIPIENT_PHONE");
            }
            // FOR SHARE: пока водитель отменяет поездку, новая заявка ждёт и увидит отмену.
            Optional<TripRow> found = jdbc.sql("""
                            SELECT driver_user_id, status, departure_from, parcel_slots_available
                            FROM trips WHERE id = :id FOR SHARE
                            """)
                    .param("id", cmd.tripId())
                    .query((rs, i) -> new TripRow(rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getInt(4)))
                    .optional();
            if (found.isEmpty() || !"PUBLISHED".equals(found.get().status())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "error.trip.notFound");
            }
            TripRow trip = found.get();
            if (trip.ownerId().equals(senderId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "OWN_TRIP", "error.parcel.own_trip");
            }
            if (!trip.departureFrom().isAfter(clock.instant())) {
                throw new ApiException(HttpStatus.CONFLICT, "TRIP_DEPARTED", "error.parcel.trip_departed");
            }
            if (trip.parcelSlotsAvailable() <= 0) {
                throw new ApiException(HttpStatus.CONFLICT, "NO_PARCEL_CAPACITY", "error.parcel.no_capacity");
            }
            // Получатель не может быть ни отправителем, ни водителем: иначе код доставки подтвердил бы свой же человек.
            if (cmd.recipientPhone().equals(phoneOf(senderId))) {
                throw bad("RECIPIENT_IS_SENDER");
            }
            if (cmd.recipientPhone().equals(phoneOf(trip.ownerId()))) {
                throw bad("RECIPIENT_IS_DRIVER");
            }
            UUID id = UUID.randomUUID();
            OffsetDateTime now = utc(clock.instant());
            jdbc.sql("""
                            INSERT INTO parcel_requests (id, sender_user_id, trip_id, driver_user_id, recipient_phone, size,
                                                         category, status, obligation_version, created_at, updated_at)
                            VALUES (:id, :s, :t, :d, :phone, :size, :cat, 'REQUESTED', :ov, :now, :now)
                            """)
                    .param("id", id)
                    .param("s", senderId)
                    .param("t", cmd.tripId())
                    .param("d", trip.ownerId())
                    .param("phone", cmd.recipientPhone())
                    .param("size", cmd.size())
                    .param("cat", cmd.category())
                    .param("ov", obligationVersion)
                    .param("now", now)
                    .update();
            Parcel parcel = find(id).orElseThrow();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tripId", parcel.tripId());
            payload.put("senderId", senderId);
            payload.put("size", parcel.size());
            payload.put("category", parcel.category());
            payload.put("obligationVersion", obligationVersion);
            events.append(AGGREGATE, id, "PARCEL_REQUESTED", senderId, payload);
            return parcel;
        });
    }

    /** Водитель отменил поездку: заявки, которые ещё не у водителя, снимаются в той же транзакции. */
    @Override
    public void onTripCancelled(UUID tripId, UUID actorUserId) {
        tx.executeWithoutResult(status -> {
            List<IdStatus> open = jdbc.sql("""
                            SELECT id, status FROM parcel_requests
                            WHERE trip_id = :t AND status IN ('REQUESTED', 'ACCEPTED') ORDER BY id FOR UPDATE
                            """)
                    .param("t", tripId)
                    .query((rs, i) -> new IdStatus(rs.getObject(1, UUID.class), rs.getString(2)))
                    .list();
            for (IdStatus row : open) {
                jdbc.sql("UPDATE parcel_requests SET status = 'CANCELLED', updated_at = :now WHERE id = :id")
                        .param("now", utc(clock.instant()))
                        .param("id", row.id())
                        .update();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("tripId", tripId);
                payload.put("from", row.status());
                payload.put("to", "CANCELLED");
                payload.put("reason", "TRIP_CANCELLED");
                events.append(AGGREGATE, row.id(), "PARCEL_CANCELLED", actorUserId, payload);
            }
        });
    }

    // ---------- чтение ----------

    public List<Parcel> listMine(UUID senderId) {
        return jdbc.sql(SELECT_PARCEL + " WHERE sender_user_id = :s ORDER BY created_at DESC, id")
                .param("s", senderId)
                .query((rs, i) -> parcel(rs))
                .list();
    }

    /** Заявки на свою поездку (водителю). Чужая поездка это 404. */
    public List<Parcel> listForTrip(UUID driverId, UUID tripId) {
        Optional<UUID> owner = jdbc.sql("SELECT driver_user_id FROM trips WHERE id = :id")
                .param("id", tripId)
                .query(UUID.class)
                .optional();
        if (owner.isEmpty() || !owner.get().equals(driverId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "error.trip.notFound");
        }
        return jdbc.sql(SELECT_PARCEL + " WHERE trip_id = :t ORDER BY created_at, id")
                .param("t", tripId)
                .query((rs, i) -> parcel(rs))
                .list();
    }

    /** Заявку видят только её отправитель и назначенный водитель. */
    public Optional<Parcel> findVisible(UUID userId, UUID parcelId) {
        return jdbc.sql(SELECT_PARCEL + " WHERE id = :id AND (sender_user_id = :u OR driver_user_id = :u)")
                .param("id", parcelId)
                .param("u", userId)
                .query((rs, i) -> parcel(rs))
                .optional();
    }

    // ---------- внутреннее ----------

    Optional<Parcel> find(UUID id) {
        return jdbc.sql(SELECT_PARCEL + " WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> parcel(rs))
                .optional();
    }

    static String toJson(Parcel p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("tripId", p.tripId());
        m.put("senderId", p.senderId());
        m.put("size", p.size());
        m.put("category", p.category());
        m.put("status", p.status());
        m.put("createdAt", p.createdAt());
        m.put("updatedAt", p.updatedAt());
        return Json.write(m);
    }

    private boolean categoryActive(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM parcel_categories WHERE code = :c AND active)")
                .param("c", code)
                .query(Boolean.class)
                .single();
    }

    private String phoneOf(UUID userId) {
        return jdbc.sql("SELECT phone FROM users WHERE id = :id").param("id", userId).query(String.class).single();
    }

    private static Parcel parcel(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Parcel(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), ParcelStatus.valueOf(rs.getString(6)),
                rs.getObject(7, OffsetDateTime.class).toInstant(), rs.getObject(8, OffsetDateTime.class).toInstant());
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "error.parcel." + code.toLowerCase());
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
