package kg.jolomo.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kg.jolomo.shared.ApiException;
import kg.jolomo.sms.SmsOutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Телефонный OTP (SP02-02, SP02-03). Код создаёт сервер, хранится только HMAC-хеш,
 * код одноразовый, привязан к телефону и purpose. Бюджет попыток и лимит повторной
 * отправки считаются по БД, поэтому переживают рестарт.
 */
@Service
public class OtpService {

    public record Issued(UUID challengeId, Instant expiresAt, long resendAfterSeconds) {}

    public enum VerifyResult { OK, INVALID, EXPIRED, EXHAUSTED }

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final SmsOutboxService sms;
    private final Clock clock;
    private final OtpSettings settings;
    private final SecureRandom random = new SecureRandom();

    public OtpService(
            JdbcClient jdbc, TransactionOperations tx, SmsOutboxService sms, Clock clock, OtpSettings settings) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sms = sms;
        this.clock = clock;
        this.settings = settings;
    }

    public Issued request(String phone, Purpose purpose) {
        return tx.execute(status -> {
            // Один запрос на телефон и purpose за раз: лимиты не обойти параллельными вызовами.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:k))")
                    .param("k", phone + "|" + purpose)
                    .query((rs, i) -> 1)
                    .list();

            Instant now = clock.instant();
            Optional<Instant> latest = jdbc.sql("""
                            SELECT max(created_at) FROM auth_challenges
                            WHERE phone = :phone AND purpose = :purpose
                            """)
                    .param("phone", phone)
                    .param("purpose", purpose.name())
                    .query((rs, i) -> {
                        OffsetDateTime t = rs.getObject(1, OffsetDateTime.class);
                        return t == null ? null : t.toInstant();
                    })
                    .optional();
            if (latest.isPresent() && now.isBefore(latest.get().plus(settings.resendInterval()))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_TOO_SOON", "error.otp.too_soon");
            }
            Integer lastHour = jdbc.sql("""
                            SELECT count(*) FROM auth_challenges
                            WHERE phone = :phone AND purpose = :purpose AND created_at > :since
                            """)
                    .param("phone", phone)
                    .param("purpose", purpose.name())
                    .param("since", utc(now.minusSeconds(3600)))
                    .query(Integer.class)
                    .single();
            if (lastHour >= settings.maxPerHour()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RESEND_LIMIT", "error.otp.resend_limit");
            }

            jdbc.sql("""
                            UPDATE auth_challenges SET status = 'SUPERSEDED', code_hash = NULL
                            WHERE phone = :phone AND purpose = :purpose AND status = 'ACTIVE'
                            """)
                    .param("phone", phone)
                    .param("purpose", purpose.name())
                    .update();

            UUID id = UUID.randomUUID();
            String code = String.format("%06d", random.nextInt(1_000_000));
            Instant expiresAt = now.plus(settings.ttl());
            jdbc.sql("""
                            INSERT INTO auth_challenges
                                (id, phone, purpose, code_hash, expires_at, attempts_left, status, created_at)
                            VALUES (:id, :phone, :purpose, :hash, :exp, :attempts, 'ACTIVE', :now)
                            """)
                    .param("id", id)
                    .param("phone", phone)
                    .param("purpose", purpose.name())
                    .param("hash", hash(id, phone, purpose, code))
                    .param("exp", utc(expiresAt))
                    .param("attempts", settings.maxAttempts())
                    .param("now", utc(now))
                    .update();

            sms.enqueue("otp:" + id, phone, "JolOmo: ваш код " + code + ". Никому его не сообщайте.");
            return new Issued(id, expiresAt, settings.resendInterval().toSeconds());
        });
    }

    /**
     * Результат возвращается, а не бросается как исключение: иначе откат транзакции
     * стёр бы списанную попытку.
     */
    public VerifyResult verify(UUID challengeId, String phone, Purpose purpose, String code) {
        return tx.execute(status -> {
            Optional<Row> found = jdbc.sql("""
                            SELECT phone, purpose, code_hash, expires_at, attempts_left, status
                            FROM auth_challenges WHERE id = :id FOR UPDATE
                            """)
                    .param("id", challengeId)
                    .query((rs, i) -> new Row(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant(),
                            rs.getInt(5),
                            rs.getString(6)))
                    .optional();
            if (found.isEmpty()) {
                return VerifyResult.INVALID;
            }
            Row r = found.get();
            if (!r.phone().equals(phone) || !r.purpose().equals(purpose.name())) {
                return VerifyResult.INVALID;
            }
            if (!"ACTIVE".equals(r.status())) {
                return "EXHAUSTED".equals(r.status())
                        ? VerifyResult.EXHAUSTED
                        : "EXPIRED".equals(r.status()) ? VerifyResult.EXPIRED : VerifyResult.INVALID;
            }
            Instant now = clock.instant();
            if (!now.isBefore(r.expiresAt())) {
                finish(challengeId, "EXPIRED", null);
                return VerifyResult.EXPIRED;
            }
            if (r.codeHash() != null && constantTimeEquals(r.codeHash(), hash(challengeId, phone, purpose, code))) {
                finish(challengeId, "CONSUMED", now);
                return VerifyResult.OK;
            }
            int left = r.attemptsLeft() - 1;
            if (left <= 0) {
                jdbc.sql("UPDATE auth_challenges SET attempts_left = 0, status = 'EXHAUSTED', code_hash = NULL WHERE id = :id")
                        .param("id", challengeId)
                        .update();
                return VerifyResult.EXHAUSTED;
            }
            jdbc.sql("UPDATE auth_challenges SET attempts_left = :left WHERE id = :id")
                    .param("left", left)
                    .param("id", challengeId)
                    .update();
            return VerifyResult.INVALID;
        });
    }

    /** Материал проверки не должен жить дольше суток после истечения (ТЗ). Вызывается по расписанию. */
    public int purgeStaleMaterial() {
        return jdbc.sql("""
                        UPDATE auth_challenges SET code_hash = NULL
                        WHERE code_hash IS NOT NULL AND expires_at < :cutoff
                        """)
                .param("cutoff", utc(clock.instant().minusSeconds(3600)))
                .update();
    }

    private void finish(UUID id, String newStatus, Instant consumedAt) {
        if (consumedAt == null) {
            jdbc.sql("UPDATE auth_challenges SET status = :status, code_hash = NULL WHERE id = :id")
                    .param("status", newStatus)
                    .param("id", id)
                    .update();
        } else {
            jdbc.sql("UPDATE auth_challenges SET status = :status, code_hash = NULL, consumed_at = :at WHERE id = :id")
                    .param("status", newStatus)
                    .param("at", utc(consumedAt))
                    .param("id", id)
                    .update();
        }
    }

    private String hash(UUID id, String phone, Purpose purpose, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.pepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal((id + "|" + phone + "|" + purpose + "|" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC недоступен", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private record Row(String phone, String purpose, String codeHash, Instant expiresAt, int attemptsLeft, String status) {}
}
