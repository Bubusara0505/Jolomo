package kg.jolomo.users;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Legal hold (SP04-03): пока у пользователя есть действующий hold, ни одно задание удаления его не трогает.
 * Hold ставит и снимает администратор с правом LEGAL_HOLD (только владелец), с кодом причины и строкой аудита.
 * Свободного текста нет. Строка пользователя блокируется на время операции, поэтому hold и удаление не обгоняют друг друга.
 */
@Service
public class LegalHoldService {

    public record Hold(UUID id, UUID userId, String reasonCode, UUID placedBy, Instant placedAt, UUID releasedBy, Instant releasedAt) {}

    private static final Pattern REASON = Pattern.compile("^[A-Z0-9_]{3,64}$");

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;

    public LegalHoldService(JdbcClient jdbc, TransactionOperations tx, Clock clock, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
    }

    public Hold place(UUID adminId, UUID userId, String reasonCode) {
        requireReason(reasonCode);
        return tx.execute(status -> {
            lockUser(userId);
            if (activeHoldExists(userId)) {
                throw new ApiException(HttpStatus.CONFLICT, "LEGAL_HOLD_ALREADY_ACTIVE", "error.legal_hold.already_active");
            }
            UUID id = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            jdbc.sql("INSERT INTO legal_holds (id, user_id, reason_code, placed_by, placed_at) VALUES (:id, :u, :r, :a, :now)")
                    .param("id", id)
                    .param("u", userId)
                    .param("r", reasonCode)
                    .param("a", adminId)
                    .param("now", now)
                    .update();
            audit.record(adminId, "LEGAL_HOLD_PLACED", "USER", userId, reasonCode, null, "HELD");
            return new Hold(id, userId, reasonCode, adminId, now.toInstant(), null, null);
        });
    }

    public Hold release(UUID adminId, UUID holdId, String reasonCode) {
        requireReason(reasonCode);
        return tx.execute(status -> {
            UUID userId = jdbc.sql("SELECT user_id FROM legal_holds WHERE id = :id")
                    .param("id", holdId)
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LEGAL_HOLD_NOT_FOUND", "error.legal_hold.not_found"));
            jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE").param("id", userId).query(UUID.class).optional();
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            int changed = jdbc.sql("UPDATE legal_holds SET released_by = :a, released_at = :now WHERE id = :id AND released_at IS NULL")
                    .param("a", adminId)
                    .param("now", now)
                    .param("id", holdId)
                    .update();
            if (changed == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "LEGAL_HOLD_ALREADY_RELEASED", "error.legal_hold.already_released");
            }
            audit.record(adminId, "LEGAL_HOLD_RELEASED", "USER", userId, reasonCode, "HELD", "RELEASED");
            return get(holdId);
        });
    }

    public List<Hold> list(UUID userId) {
        return jdbc.sql("""
                        SELECT id, user_id, reason_code, placed_by, placed_at, released_by, released_at
                        FROM legal_holds WHERE user_id = :u ORDER BY placed_at DESC, id
                        """)
                .param("u", userId)
                .query(LegalHoldService::map)
                .list();
    }

    private Hold get(UUID id) {
        return jdbc.sql("""
                        SELECT id, user_id, reason_code, placed_by, placed_at, released_by, released_at
                        FROM legal_holds WHERE id = :id
                        """)
                .param("id", id)
                .query(LegalHoldService::map)
                .single();
    }

    private static Hold map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        OffsetDateTime released = rs.getObject(7, OffsetDateTime.class);
        return new Hold(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, OffsetDateTime.class).toInstant(),
                rs.getObject(6, UUID.class), released == null ? null : released.toInstant());
    }

    private void lockUser(UUID userId) {
        boolean exists = userId != null && jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query(UUID.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "error.user.not_found");
        }
    }

    private boolean activeHoldExists(UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_holds WHERE user_id = :u AND released_at IS NULL)")
                .param("u", userId)
                .query(Boolean.class)
                .single();
    }

    private static void requireReason(String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "error.reason.required");
        }
    }
}
