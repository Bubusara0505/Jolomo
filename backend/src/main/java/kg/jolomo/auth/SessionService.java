package kg.jolomo.auth;

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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Сессии (SP02-04). Access живёт 15 минут и подписан HMAC. Refresh случайный, в базе только его SHA-256.
 * Refresh ротируется; повторное использование уже обмененного токена отзывает всё семейство.
 */
@Service
public class SessionService {

    public record Tokens(UUID userId, String access, String refresh, Duration accessTtl, Duration refreshTtl) {}

    public record Principal(UUID userId, UUID familyId) {}

    public record MeView(UUID id, String phone) {}

    public enum RefreshOutcome { OK, INVALID, REUSED }

    public record RefreshResult(RefreshOutcome outcome, Tokens tokens) {}

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final SessionSettings settings;
    private final SecureRandom random = new SecureRandom();

    public SessionService(JdbcClient jdbc, TransactionOperations tx, Clock clock, SessionSettings settings) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.settings = settings;
    }

    /** Вызывается после успешной проверки OTP: создаёт пользователя при первом входе и новое семейство. */
    public Tokens startSession(String phone) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            UserRow user = jdbc.sql("""
                            INSERT INTO users (id, phone, status, created_at, last_login_at)
                            VALUES (:id, :phone, 'ACTIVE', :now, :now)
                            ON CONFLICT (phone) DO UPDATE SET last_login_at = EXCLUDED.last_login_at
                            RETURNING id, status
                            """)
                    .param("id", UUID.randomUUID())
                    .param("phone", phone)
                    .param("now", utc(now))
                    .query((rs, i) -> new UserRow(rs.getObject(1, UUID.class), rs.getString(2)))
                    .single();
            if (!"ACTIVE".equals(user.status())) {
                // Исключение откатывает транзакцию, строка пользователя не меняется.
                throw new ApiException(HttpStatus.FORBIDDEN, "USER_SUSPENDED", "error.user.suspended");
            }
            return issue(user.id(), UUID.randomUUID(), now);
        });
    }

    /**
     * Результат возвращается, а не бросается: иначе откат стёр бы отзыв семейства при повторном использовании.
     * Два параллельных refresh одним токеном считаются повтором, клиент обязан отправлять refresh по одному.
     */
    public RefreshResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return new RefreshResult(RefreshOutcome.INVALID, null);
        }
        return tx.execute(status -> {
            Optional<RefreshRow> found = jdbc.sql("""
                            SELECT s.user_id, s.family_id, s.status, s.expires_at, u.status
                            FROM refresh_sessions s JOIN users u ON u.id = s.user_id
                            WHERE s.token_hash = :h
                            FOR UPDATE OF s
                            """)
                    .param("h", sha256(refreshToken))
                    .query((rs, i) -> new RefreshRow(
                            rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class),
                            rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant(),
                            rs.getString(5)))
                    .optional();
            if (found.isEmpty()) {
                return new RefreshResult(RefreshOutcome.INVALID, null);
            }
            RefreshRow r = found.get();
            if ("ROTATED".equals(r.status())) {
                revokeFamily(r.familyId());
                return new RefreshResult(RefreshOutcome.REUSED, null);
            }
            Instant now = clock.instant();
            if (!"ACTIVE".equals(r.status()) || !now.isBefore(r.expiresAt()) || !"ACTIVE".equals(r.userStatus())) {
                revokeFamily(r.familyId());
                return new RefreshResult(RefreshOutcome.INVALID, null);
            }
            jdbc.sql("UPDATE refresh_sessions SET status = 'ROTATED', rotated_at = :now WHERE token_hash = :h")
                    .param("now", utc(now))
                    .param("h", sha256(refreshToken))
                    .update();
            return new RefreshResult(RefreshOutcome.OK, issue(r.userId(), r.familyId(), now));
        });
    }

    /** Выход: отзывает всё семейство. Неизвестный токен молча игнорируется. */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        tx.executeWithoutResult(status -> jdbc.sql("SELECT family_id FROM refresh_sessions WHERE token_hash = :h")
                .param("h", sha256(refreshToken))
                .query((rs, i) -> rs.getObject(1, UUID.class))
                .optional()
                .ifPresent(this::revokeFamily));
    }

    /** Проверяет подпись, срок и то, что семейство не отозвано, а пользователь не приостановлен. */
    public Optional<Principal> authenticate(String accessToken) {
        if (accessToken == null) {
            return Optional.empty();
        }
        String[] parts = accessToken.split("\\.", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            String payload = new String(B64D.decode(parts[0]), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(B64D.decode(parts[1]), mac(payload))) {
                return Optional.empty();
            }
            String[] f = payload.split("\\.", -1);
            if (f.length != 3) {
                return Optional.empty();
            }
            UUID userId = UUID.fromString(f[0]);
            UUID familyId = UUID.fromString(f[1]);
            Instant exp = Instant.ofEpochSecond(Long.parseLong(f[2]));
            if (!clock.instant().isBefore(exp)) {
                return Optional.empty();
            }
            Integer live = jdbc.sql("""
                            SELECT count(*) FROM refresh_sessions s JOIN users u ON u.id = s.user_id
                            WHERE s.family_id = :f AND s.user_id = :u AND s.status = 'ACTIVE' AND u.status = 'ACTIVE'
                            """)
                    .param("f", familyId)
                    .param("u", userId)
                    .query(Integer.class)
                    .single();
            return live > 0 ? Optional.of(new Principal(userId, familyId)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Optional<MeView> me(UUID userId) {
        return jdbc.sql("SELECT id, phone FROM users WHERE id = :id")
                .param("id", userId)
                .query((rs, i) -> new MeView(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional();
    }

    private Tokens issue(UUID userId, UUID familyId, Instant now) {
        String refresh = randomToken();
        jdbc.sql("""
                        INSERT INTO refresh_sessions (id, user_id, family_id, token_hash, status, created_at, expires_at)
                        VALUES (:id, :u, :f, :h, 'ACTIVE', :now, :exp)
                        """)
                .param("id", UUID.randomUUID())
                .param("u", userId)
                .param("f", familyId)
                .param("h", sha256(refresh))
                .param("now", utc(now))
                .param("exp", utc(now.plus(settings.refreshTtl())))
                .update();
        Instant accessExp = now.plus(settings.accessTtl());
        String payload = userId + "." + familyId + "." + accessExp.getEpochSecond();
        String access = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(mac(payload));
        return new Tokens(userId, access, refresh, settings.accessTtl(), settings.refreshTtl());
    }

    private void revokeFamily(UUID familyId) {
        jdbc.sql("UPDATE refresh_sessions SET status = 'REVOKED' WHERE family_id = :f AND status <> 'REVOKED'")
                .param("f", familyId)
                .update();
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(("jl-access-v1|" + payload).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC недоступен", e);
        }
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return B64.encodeToString(b);
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private record UserRow(UUID id, String status) {}

    private record RefreshRow(UUID userId, UUID familyId, String status, Instant expiresAt, String userStatus) {}
}
