package kg.jolomo.users;

import java.time.Clock;
import java.time.Instant;
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
 * Версия уведомления о данных (SP04-03). Реестр версий лежит в privacy_notices, а принятая версия хранится у самого
 * пользователя. Текст уведомления в базе не хранится: он живёт в каталогах KY/RU/EN. Каждое изменение версии у
 * пользователя пишет строку аудита (кто, когда, с какой версии на какую).
 */
@Service
public class PrivacyNoticeService {

    public record Notice(String version, Instant publishedAt) {}

    public record Acceptance(String version, Instant acceptedAt) {}

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;

    public PrivacyNoticeService(JdbcClient jdbc, TransactionOperations tx, Clock clock, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
    }

    public Optional<Notice> current() {
        return jdbc.sql("SELECT version, published_at FROM privacy_notices WHERE is_current")
                .query((rs, i) -> new Notice(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Версия, которую пользователь принял, или пусто, если не принимал. */
    public Optional<Acceptance> acceptanceOf(UUID userId) {
        return jdbc.sql("SELECT notice_version, notice_accepted_at FROM users WHERE id = :id AND notice_version IS NOT NULL")
                .param("id", userId)
                .query((rs, i) -> new Acceptance(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Пользователь принимает версию. Принять можно только действующую версию. Повтор той же версии ничего не меняет
     * и аудит не дублирует.
     */
    public Acceptance accept(UUID userId, String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTICE_VERSION_INVALID", "error.notice.invalid_version");
        }
        return tx.execute(status -> {
            String previous = jdbc.sql("SELECT coalesce(notice_version, '') FROM users WHERE id = :id FOR UPDATE")
                    .param("id", userId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "error.user.not_found"));
            boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM privacy_notices WHERE version = :v)")
                    .param("v", version)
                    .query(Boolean.class)
                    .single();
            if (!known) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOTICE_VERSION_UNKNOWN", "error.notice.unknown_version");
            }
            boolean isCurrent = jdbc.sql("SELECT EXISTS (SELECT 1 FROM privacy_notices WHERE version = :v AND is_current)")
                    .param("v", version)
                    .query(Boolean.class)
                    .single();
            if (!isCurrent) {
                throw new ApiException(HttpStatus.CONFLICT, "NOTICE_NOT_CURRENT", "error.notice.not_current");
            }
            if (previous.equals(version)) {
                return acceptanceOf(userId).orElseThrow();
            }
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            jdbc.sql("UPDATE users SET notice_version = :v, notice_accepted_at = :now WHERE id = :id")
                    .param("v", version)
                    .param("now", now)
                    .param("id", userId)
                    .update();
            audit.record(userId, "PRIVACY_NOTICE_ACCEPTED", "USER", userId, "SELF_SERVICE",
                    previous.isEmpty() ? null : previous, version);
            return new Acceptance(version, now.toInstant());
        });
    }

    /**
     * Публикация новой версии (операция владельца, HTTP-маршрута нет): предыдущая перестаёт быть действующей, принятые
     * пользователями версии остаются как были, повторного согласия автоматически никто не получает.
     */
    public Notice publish(UUID adminId, String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTICE_VERSION_INVALID", "error.notice.invalid_version");
        }
        return tx.execute(status -> {
            boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM privacy_notices WHERE version = :v)")
                    .param("v", version)
                    .query(Boolean.class)
                    .single();
            if (exists) {
                throw new ApiException(HttpStatus.CONFLICT, "NOTICE_VERSION_EXISTS", "error.notice.exists");
            }
            String previous = current().map(Notice::version).orElse(null);
            jdbc.sql("UPDATE privacy_notices SET is_current = false WHERE is_current").update();
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            jdbc.sql("INSERT INTO privacy_notices (version, published_at, is_current) VALUES (:v, :now, true)")
                    .param("v", version)
                    .param("now", now)
                    .update();
            audit.record(adminId, "PRIVACY_NOTICE_PUBLISHED", "PRIVACY_NOTICE", null, "NOTICE_PUBLISHED", previous, version);
            return new Notice(version, now.toInstant());
        });
    }
}
