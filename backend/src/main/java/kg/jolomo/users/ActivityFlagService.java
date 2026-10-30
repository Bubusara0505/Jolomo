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
 * Подозрительная активность (SP11-01): поддержка помечает пользователя. Это только запись с кодом причины и строкой аудита.
 * Пометка ничего не блокирует и не меняет у пользователя, водителя, посылок и вместимости; приостановка делается отдельным
 * действием. Свободного текста нет. Право ACTIVITY_FLAG проверяет контроллер.
 */
@Service
public class ActivityFlagService {

    public record Flag(UUID id, UUID userId, String kind, String reasonCode, UUID recordedBy, Instant createdAt) {}

    private static final Pattern REASON = Pattern.compile("^[A-Z0-9_]{3,64}$");

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;

    public ActivityFlagService(JdbcClient jdbc, TransactionOperations tx, Clock clock, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
    }

    public Flag record(UUID adminId, UUID userId, String kind, String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "error.reason.required");
        }
        if (kind == null || !kindActive(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SUSPICION_KIND", "error.suspicion.invalid_kind");
        }
        return tx.execute(status -> {
            boolean exists = userId != null && jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id)")
                    .param("id", userId)
                    .query(Boolean.class)
                    .single();
            if (!exists) {
                throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "error.user.not_found");
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO suspicious_activity (id, user_id, kind, reason_code, recorded_by, created_at)
                            VALUES (:id, :u, :kind, :reason, :admin, :now)
                            """)
                    .param("id", id)
                    .param("u", userId)
                    .param("kind", kind)
                    .param("reason", reasonCode)
                    .param("admin", adminId)
                    .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .update();
            audit.record(adminId, "SUSPICIOUS_ACTIVITY_FLAGGED", "USER", userId, reasonCode, null, kind);
            return new Flag(id, userId, kind, reasonCode, adminId, clock.instant());
        });
    }

    public List<Flag> list(UUID userId, int limit) {
        return jdbc.sql("""
                        SELECT id, user_id, kind, reason_code, recorded_by, created_at FROM suspicious_activity
                        WHERE user_id = :u ORDER BY created_at DESC, id LIMIT :limit
                        """)
                .param("u", userId)
                .param("limit", Math.max(1, Math.min(limit, 100)))
                .query((rs, i) -> new Flag(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, OffsetDateTime.class).toInstant()))
                .list();
    }

    private boolean kindActive(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM suspicion_kinds WHERE code = :c AND active)")
                .param("c", code)
                .query(Boolean.class)
                .single();
    }
}
