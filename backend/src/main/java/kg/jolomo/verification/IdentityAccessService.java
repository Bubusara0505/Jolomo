package kg.jolomo.verification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
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
 * Выдача документа личности администратору (SP04-02, T-08). Две ступени: grant (проверка права, цель, аудит) даёт
 * токен на 5 минут, привязанный к этому администратору и объекту; open отдаёт байты и каждый раз снова проверяет
 * срок, владельца токена и состояние заявки, и пишет строку аудита. Право администратора и его сессию проверяет
 * вызывающий контроллер (AdminAuth). Отказы тоже попадают в аудит (вне транзакции, чтобы запись осталась).
 */
@Service
public class IdentityAccessService {

    public static final Duration TTL = Duration.ofMinutes(5);
    public static final String PATH_PREFIX = "/api/v1/admin/verification/documents/";

    public record Grant(String token, String path, Instant expiresAt) {}

    public record Download(UUID objectId, String contentType, byte[] bytes) {}

    private static final Pattern REASON = Pattern.compile("^[A-Z0-9_]{3,64}$");

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;
    private final IdentityFileStore store;
    private final SecureRandom random = new SecureRandom();

    IdentityAccessService(JdbcClient jdbc, TransactionOperations tx, Clock clock, AuditLog audit, IdentityFileStore store) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
        this.store = store;
    }

    public Grant grant(UUID adminId, UUID objectId, String purposeCode) {
        if (purposeCode == null || !REASON.matcher(purposeCode).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "error.reason.required");
        }
        var row = jdbc.sql("""
                        SELECT o.state, c.status FROM identity_objects o JOIN verification_cases c ON c.id = o.case_id
                        WHERE o.id = :id
                        """)
                .param("id", objectId)
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)})
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OBJECT_NOT_FOUND", "error.object.notFound"));
        if (!isAvailable(row[0], row[1])) {
            audit.record(adminId, "IDENTITY_ACCESS_DENIED", "IDENTITY_OBJECT", objectId, "OBJECT_UNAVAILABLE", null, null);
            throw unavailable();
        }
        String token = randomToken();
        Instant now = clock.instant();
        Instant expires = now.plus(TTL);
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO identity_access_grants (id, token_hash, object_id, admin_id, purpose_code, created_at, expires_at)
                            VALUES (:id, :h, :o, :a, :p, :now, :exp)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("h", sha256(token))
                    .param("o", objectId)
                    .param("a", adminId)
                    .param("p", purposeCode)
                    .param("now", utc(now))
                    .param("exp", utc(expires))
                    .update();
            audit.record(adminId, "IDENTITY_ACCESS_GRANTED", "IDENTITY_OBJECT", objectId, purposeCode, null, null);
        });
        return new Grant(token, PATH_PREFIX + token, expires);
    }

    public Download open(UUID adminId, String token) {
        Optional<GrantRow> found = jdbc.sql("""
                        SELECT g.object_id, g.admin_id, g.expires_at, o.state, c.status, o.content_type
                        FROM identity_access_grants g
                        JOIN identity_objects o ON o.id = g.object_id
                        JOIN verification_cases c ON c.id = o.case_id
                        WHERE g.token_hash = :h
                        """)
                .param("h", sha256(token == null ? "" : token))
                .query((rs, i) -> new GrantRow(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
        if (found.isEmpty()) {
            audit.record(adminId, "IDENTITY_ACCESS_DENIED", "IDENTITY_OBJECT", null, "UNKNOWN_TOKEN", null, null);
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCESS_NOT_FOUND", "error.access.notFound");
        }
        GrantRow g = found.get();
        if (!g.adminId().equals(adminId)) {
            // Токен чужой: тот же ответ, что на неизвестный, но в аудите видно, кто пробовал.
            audit.record(adminId, "IDENTITY_ACCESS_DENIED", "IDENTITY_OBJECT", g.objectId(), "WRONG_ADMIN", null, null);
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCESS_NOT_FOUND", "error.access.notFound");
        }
        if (!clock.instant().isBefore(g.expiresAt())) {
            audit.record(adminId, "IDENTITY_ACCESS_DENIED", "IDENTITY_OBJECT", g.objectId(), "EXPIRED", null, null);
            throw new ApiException(HttpStatus.GONE, "ACCESS_EXPIRED", "error.access.expired");
        }
        if (!isAvailable(g.state(), g.caseStatus())) {
            audit.record(adminId, "IDENTITY_ACCESS_DENIED", "IDENTITY_OBJECT", g.objectId(), "OBJECT_UNAVAILABLE", null, null);
            throw unavailable();
        }
        // Запись аудита до выдачи байтов: просмотр без следа невозможен.
        audit.record(adminId, "IDENTITY_FILE_VIEWED", "IDENTITY_OBJECT", g.objectId(), "WITHIN_GRANT", null, null);
        return new Download(g.objectId(), g.contentType(), store.read(g.objectId()));
    }

    /** Документы доступны, пока заявка открыта (SUBMITTED или IN_REVIEW). После решения доступа нет. */
    private static boolean isAvailable(String objectState, String caseStatus) {
        return "STORED".equals(objectState) && ("SUBMITTED".equals(caseStatus) || "IN_REVIEW".equals(caseStatus));
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.GONE, "OBJECT_UNAVAILABLE", "error.object.unavailable");
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private record GrantRow(UUID objectId, UUID adminId, Instant expiresAt, String state, String caseStatus, String contentType) {}
}
