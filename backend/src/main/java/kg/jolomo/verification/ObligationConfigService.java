package kg.jolomo.verification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Выключатель юриста (obligation_config). По умолчанию выключен. Загружает только сервер; HTTP-маршрута,
 * которым клиент мог бы его включить, нет и быть не должно (проверяет тест).
 */
@Service
public class ObligationConfigService {

    public static final String TRIP_PUBLISH = "TRIP_PUBLISH";
    public static final String PARCEL_CREATE = "PARCEL_CREATE";

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;

    public ObligationConfigService(JdbcClient jdbc, TransactionOperations tx, Clock clock, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
    }

    public boolean isEnabled(String key) {
        return jdbc.sql("SELECT enabled FROM obligation_config WHERE key = :k")
                .param("k", key)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /** Версия загруженного набора, если выключатель включён; иначе пусто (default deny). */
    public java.util.Optional<String> enabledVersion(String key) {
        return jdbc.sql("SELECT version FROM obligation_config WHERE key = :k AND enabled")
                .param("k", key)
                .query(String.class)
                .optional();
    }

    /** Загрузка набора обязательств по подписанному заключению. Версия обязательна. */
    public void load(String key, String version, UUID actorUserId) {
        if (version == null || version.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "error.version.required");
        }
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            UPDATE obligation_config SET enabled = true, version = :v, loaded_at = :now, loaded_by = :a
                            WHERE key = :k
                            """)
                    .param("v", version)
                    .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .param("a", actorUserId)
                    .param("k", key)
                    .update();
            if (updated != 1) {
                throw new ApiException(HttpStatus.NOT_FOUND, "OBLIGATION_KEY_UNKNOWN", "error.obligation.unknown");
            }
            audit.record(actorUserId, "OBLIGATION_LOADED", "OBLIGATION_CONFIG", null, "LEGAL_OUTCOME", "OFF", "ON");
        });
    }

    public void disable(String key, UUID actorUserId, String reasonCode) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("UPDATE obligation_config SET enabled = false WHERE key = :k").param("k", key).update();
            audit.record(actorUserId, "OBLIGATION_DISABLED", "OBLIGATION_CONFIG", null, reasonCode, "ON", "OFF");
        });
    }
}
