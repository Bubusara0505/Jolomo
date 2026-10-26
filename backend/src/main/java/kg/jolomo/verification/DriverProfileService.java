package kg.jolomo.verification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Профиль водителя и переходы допуска. Каждый переход под блокировкой строки, с кодом причины и строкой аудита.
 * Переходы проверяющего (IN_REVIEW, APPROVED, REJECTED, приостановка) вызывает только админская часть (SP04-01);
 * пользовательского маршрута к ним нет.
 */
@Service
public class DriverProfileService {

    public record Profile(UUID userId, DriverProfileStatus status) {}

    private static final Pattern REASON = Pattern.compile("^[A-Z0-9_]{3,64}$");

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;

    public DriverProfileService(JdbcClient jdbc, TransactionOperations tx, Clock clock, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
    }

    /** Идемпотентно: повторный вызов возвращает уже существующий профиль. */
    public Profile create(UUID userId) {
        return tx.execute(status -> {
            int inserted = jdbc.sql("""
                            INSERT INTO driver_profiles (user_id, status, created_at, updated_at)
                            VALUES (:u, 'UNVERIFIED', :now, :now)
                            ON CONFLICT (user_id) DO NOTHING
                            """)
                    .param("u", userId)
                    .param("now", now())
                    .update();
            if (inserted == 1) {
                audit.record(userId, "DRIVER_PROFILE_CREATED", "DRIVER_PROFILE", userId, "SELF_SERVICE", null, "UNVERIFIED");
            }
            return find(userId).orElseThrow();
        });
    }

    public Optional<Profile> find(UUID userId) {
        return jdbc.sql("SELECT status FROM driver_profiles WHERE user_id = :u")
                .param("u", userId)
                .query((rs, i) -> new Profile(userId, DriverProfileStatus.valueOf(rs.getString(1))))
                .optional();
    }

    /** Обычный переход по таблице {@link DriverProfileStatus#next()}. */
    public Profile transition(UUID userId, DriverProfileStatus to, UUID actorUserId, String reasonCode) {
        requireReason(reasonCode);
        return tx.execute(status -> {
            Locked row = lock(userId);
            if (!row.status().next().contains(to)) {
                throw invalid();
            }
            change(userId, row.status(), to, null);
            audit.record(actorUserId, "DRIVER_PROFILE_TRANSITION", "DRIVER_PROFILE", userId, reasonCode,
                    row.status().name(), to.name());
            return new Profile(userId, to);
        });
    }

    /** Приостановка из любого состояния; прежнее состояние запоминается. */
    public Profile suspend(UUID userId, UUID actorUserId, String reasonCode) {
        requireReason(reasonCode);
        return tx.execute(status -> {
            Locked row = lock(userId);
            if (row.status() == DriverProfileStatus.SUSPENDED) {
                throw invalid();
            }
            change(userId, row.status(), DriverProfileStatus.SUSPENDED, row.status().name());
            audit.record(actorUserId, "DRIVER_SUSPENDED", "DRIVER_PROFILE", userId, reasonCode,
                    row.status().name(), DriverProfileStatus.SUSPENDED.name());
            return new Profile(userId, DriverProfileStatus.SUSPENDED);
        });
    }

    /** Снятие приостановки возвращает прежнее состояние, а не выдаёт допуск заново. */
    public Profile liftSuspension(UUID userId, UUID actorUserId, String reasonCode) {
        requireReason(reasonCode);
        return tx.execute(status -> {
            Locked row = lock(userId);
            if (row.status() != DriverProfileStatus.SUSPENDED || row.before() == null) {
                throw invalid();
            }
            DriverProfileStatus restored = DriverProfileStatus.valueOf(row.before());
            change(userId, DriverProfileStatus.SUSPENDED, restored, null);
            audit.record(actorUserId, "DRIVER_SUSPENSION_LIFTED", "DRIVER_PROFILE", userId, reasonCode,
                    DriverProfileStatus.SUSPENDED.name(), restored.name());
            return new Profile(userId, restored);
        });
    }

    private Locked lock(UUID userId) {
        return jdbc.sql("SELECT status, status_before_suspension FROM driver_profiles WHERE user_id = :u FOR UPDATE")
                .param("u", userId)
                .query((rs, i) -> new Locked(DriverProfileStatus.valueOf(rs.getString(1)), rs.getString(2)))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DRIVER_PROFILE_NOT_FOUND", "error.driver.notFound"));
    }

    private void change(UUID userId, DriverProfileStatus from, DriverProfileStatus to, String before) {
        jdbc.sql("""
                        UPDATE driver_profiles
                        SET status = :to, status_before_suspension = :before, updated_at = :now
                        WHERE user_id = :u AND status = :from
                        """)
                .param("to", to.name())
                .param("before", before)
                .param("now", now())
                .param("u", userId)
                .param("from", from.name())
                .update();
    }

    private static void requireReason(String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "error.reason.required");
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "error.driver.invalidTransition");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record Locked(DriverProfileStatus status, String before) {}
}
