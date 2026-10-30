package kg.jolomo.parcels;

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
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.sms.SmsOutboxService;
import kg.jolomo.verification.EligibilityService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Код доставки (SP09-02, SP09-03). Доказательство доставки даёт только получатель: код приходит ему по SMS, ответа
 * с кодом нет нигде. Код привязан к посылке, номеру получателя и переходу DELIVERY_PENDING -> DELIVERED (HMAC с
 * отдельным секретом), потребляется один раз. Три ошибки блокируют проверку (VERIFICATION_LOCKED) и никогда не ведут
 * к DISPUTED. Блокировки берутся в том же порядке, что в ParcelService: поездка, посылка, затем проверка.
 * Счётчик ошибок и блокировка возвращаются результатом, а не исключением: иначе откат стёр бы попытку.
 */
@Service
public class DeliveryService {

    public enum VerifyOutcome { DELIVERED, INVALID_CODE, LOCKED, EXPIRED, ALREADY_USED, NOT_FOUND }

    public record Issued(Instant expiresAt, int resendAfterSeconds) {}

    public record ChallengeView(String state, Instant expiresAt) {}

    private static final Pattern REASON = Pattern.compile("^[A-Z0-9_]{3,64}$");
    private static final String AGGREGATE_SOURCE = "RECIPIENT_PROOF";

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final ParcelService parcels;
    private final EligibilityService eligibility;
    private final AuditLog audit;
    private final SmsOutboxService sms;
    private final DeliverySettings settings;
    private final SecureRandom random = new SecureRandom();

    public DeliveryService(JdbcClient jdbc, TransactionOperations tx, Clock clock, ParcelService parcels,
                           EligibilityService eligibility, AuditLog audit, SmsOutboxService sms, DeliverySettings settings) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.parcels = parcels;
        this.eligibility = eligibility;
        this.audit = audit;
        this.sms = sms;
        this.settings = settings;
    }

    // ---------- водитель просит код для получателя ----------

    /**
     * Водитель на месте (DELIVERY_PENDING) просит отправить получателю код. Код уходит только SMS на номер из заявки;
     * водитель не получает ни кода, ни ссылки. Новый запрос отменяет прежний код. Повторы ограничены.
     */
    public Issued requestChallenge(UUID driverId, UUID parcelId) {
        return tx.execute(status -> {
            ParcelService.Locked l = parcels.lock(parcelId);
            if (!l.trip().ownerId().equals(driverId)) {
                throw ParcelService.parcelNotFound();
            }
            ParcelStatus st = l.parcel().status();
            if (st == ParcelStatus.VERIFICATION_LOCKED) {
                throw new ApiException(HttpStatus.CONFLICT, "VERIFICATION_LOCKED", "error.delivery.verification_locked");
            }
            if (st != ParcelStatus.DELIVERY_PENDING) {
                throw ParcelService.invalidState();
            }
            EligibilityService.Decision decision = eligibility.canManageParcels(driverId);
            if (!decision.allowed()) {
                throw new ApiException(HttpStatus.FORBIDDEN, decision.reason().name(),
                        "error.parcel." + decision.reason().name().toLowerCase());
            }
            Instant now = clock.instant();
            Instant last = jdbc.sql("SELECT max(created_at) FROM delivery_challenges WHERE parcel_id = :p")
                    .param("p", parcelId)
                    .query((rs, i) -> rs.getObject(1, OffsetDateTime.class))
                    .optional()
                    .map(OffsetDateTime::toInstant)
                    .orElse(null);
            if (last != null && now.isBefore(last.plus(settings.resendInterval()))) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DELIVERY_TOO_SOON", "error.delivery.too_soon");
            }
            Integer lastHour = jdbc.sql("SELECT count(*) FROM delivery_challenges WHERE parcel_id = :p AND created_at > :since")
                    .param("p", parcelId)
                    .param("since", utc(now.minusSeconds(3600)))
                    .query(Integer.class)
                    .single();
            if (lastHour >= settings.maxPerHour()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DELIVERY_RESEND_LIMIT", "error.delivery.resend_limit");
            }
            jdbc.sql("UPDATE delivery_challenges SET status = 'SUPERSEDED', code_hash = NULL WHERE parcel_id = :p AND status = 'ACTIVE'")
                    .param("p", parcelId)
                    .update();

            UUID id = UUID.randomUUID();
            String code = String.format("%06d", random.nextInt(1_000_000));
            String phone = recipientPhone(parcelId);
            Instant expires = now.plus(settings.ttl());
            jdbc.sql("""
                            INSERT INTO delivery_challenges (id, parcel_id, code_hash, status, created_at, expires_at)
                            VALUES (:id, :p, :h, 'ACTIVE', :now, :exp)
                            """)
                    .param("id", id)
                    .param("p", parcelId)
                    .param("h", hash(id, parcelId, phone, code))
                    .param("now", utc(now))
                    .param("exp", utc(expires))
                    .update();
            // Текст пока только на русском; каталоги KY/RU/EN появятся в SP11-03. SMS с просроченным кодом не шлём.
            sms.enqueue("delivery:" + id, phone,
                    "JolOmo: код получения посылки " + code + ". Подтвердите по ссылке: " + settings.linkBase() + "/" + id
                            + " Не называйте код никому, кроме получения посылки.",
                    expires);
            audit.record(driverId, "DELIVERY_CODE_SENT", "PARCEL", parcelId, AGGREGATE_SOURCE, null, null);
            parcels.emit("PARCEL_DELIVERY_CODE_SENT", l.parcel(), driverId, "DELIVERY_PENDING", "DELIVERY_PENDING", AGGREGATE_SOURCE);
            return new Issued(expires, (int) settings.resendInterval().toSeconds());
        });
    }

    // ---------- получатель без аккаунта ----------

    /** Состояние проверки для страницы получателя. Чужая, подменная или заменённая ссылка: пусто (общий not-found). */
    public Optional<ChallengeView> view(UUID challengeId) {
        if (challengeId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT status, expires_at FROM delivery_challenges WHERE id = :id")
                .param("id", challengeId)
                .query((rs, i) -> new String[] {rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant().toString()})
                .optional()
                .filter(r -> !"SUPERSEDED".equals(r[0]))
                .map(r -> {
                    Instant exp = Instant.parse(r[1]);
                    String state = "ACTIVE".equals(r[0]) && !clock.instant().isBefore(exp) ? "EXPIRED" : r[0];
                    return new ChallengeView(state, exp);
                });
    }

    /**
     * Получатель отправляет код. DELIVERED ставится в той же транзакции, что и потребление кода: двух доставок и
     * кода, потреблённого дважды, быть не может. Ошибки считает база, счётчик переживает рестарт.
     */
    public VerifyOutcome verify(UUID challengeId, String code) {
        if (challengeId == null) {
            return VerifyOutcome.NOT_FOUND;
        }
        VerifyOutcome outcome = tx.execute(status -> {
            Optional<UUID> parcelId = jdbc.sql("SELECT parcel_id FROM delivery_challenges WHERE id = :id")
                    .param("id", challengeId)
                    .query(UUID.class)
                    .optional();
            if (parcelId.isEmpty()) {
                return VerifyOutcome.NOT_FOUND;
            }
            ParcelService.Locked l = parcels.lock(parcelId.get());
            var row = jdbc.sql("""
                            SELECT status, code_hash, attempts_used, expires_at FROM delivery_challenges
                            WHERE id = :id FOR UPDATE
                            """)
                    .param("id", challengeId)
                    .query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant()))
                    .single();
            switch (row.status()) {
                case "CONSUMED":
                    return VerifyOutcome.ALREADY_USED;
                case "LOCKED":
                    return VerifyOutcome.LOCKED;
                case "EXPIRED":
                    return VerifyOutcome.EXPIRED;
                case "ACTIVE":
                    break;
                default:
                    return VerifyOutcome.NOT_FOUND;
            }
            if (l.parcel().status() != ParcelStatus.DELIVERY_PENDING) {
                return VerifyOutcome.NOT_FOUND;
            }
            Instant now = clock.instant();
            if (!now.isBefore(row.expiresAt())) {
                jdbc.sql("UPDATE delivery_challenges SET status = 'EXPIRED', code_hash = NULL WHERE id = :id")
                        .param("id", challengeId)
                        .update();
                return VerifyOutcome.EXPIRED;
            }

            boolean ok = row.hash() != null && code != null
                    && MessageDigest.isEqual(
                            row.hash().getBytes(StandardCharsets.UTF_8),
                            hash(challengeId, parcelId.get(), recipientPhone(parcelId.get()), code).getBytes(StandardCharsets.UTF_8));
            if (ok) {
                jdbc.sql("UPDATE delivery_challenges SET status = 'CONSUMED', code_hash = NULL, consumed_at = :now WHERE id = :id")
                        .param("now", utc(now))
                        .param("id", challengeId)
                        .update();
                parcels.setStatus(parcelId.get(), ParcelStatus.DELIVERED);
                audit.record(null, "PARCEL_DELIVERED", "PARCEL", parcelId.get(), "RECIPIENT_CODE", "DELIVERY_PENDING", "DELIVERED");
                parcels.emit("PARCEL_DELIVERED", parcels.find(parcelId.get()).orElseThrow(), null,
                        "DELIVERY_PENDING", "DELIVERED", "RECIPIENT_CODE");
                return VerifyOutcome.DELIVERED;
            }

            int attempts = row.attempts() + 1;
            if (attempts >= DeliverySettings.MAX_ATTEMPTS) {
                jdbc.sql("""
                                UPDATE delivery_challenges
                                SET status = 'LOCKED', attempts_used = :a, code_hash = NULL, locked_at = :now WHERE id = :id
                                """)
                        .param("a", attempts)
                        .param("now", utc(now))
                        .param("id", challengeId)
                        .update();
                // Третья ошибка: проверка заблокирована. Это не спор: DISPUTED ставит только разбор (SP09-01).
                parcels.setStatus(parcelId.get(), ParcelStatus.VERIFICATION_LOCKED);
                audit.record(null, "PARCEL_VERIFICATION_LOCKED", "PARCEL", parcelId.get(), "DELIVERY_CODE_ATTEMPTS",
                        "DELIVERY_PENDING", "VERIFICATION_LOCKED");
                parcels.emit("PARCEL_VERIFICATION_LOCKED", parcels.find(parcelId.get()).orElseThrow(), null,
                        "DELIVERY_PENDING", "VERIFICATION_LOCKED", "DELIVERY_CODE_ATTEMPTS");
                return VerifyOutcome.LOCKED;
            }
            jdbc.sql("UPDATE delivery_challenges SET attempts_used = :a WHERE id = :id")
                    .param("a", attempts)
                    .param("id", challengeId)
                    .update();
            return VerifyOutcome.INVALID_CODE;
        });
        return outcome == null ? VerifyOutcome.NOT_FOUND : outcome;
    }

    // ---------- восстановление после блокировки (SP09-03) ----------

    /**
     * Узкий путь: поддержка снимает блокировку, посылка возвращается в DELIVERY_PENDING, и водитель может запросить
     * новый код. Доставленной посылку это не делает. Код причины и строка аудита обязательны. Вызывающий контроллер
     * проверяет право администратора (INCIDENT_RECORD).
     */
    public ParcelService.Parcel unlockVerification(UUID adminId, UUID parcelId, String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "error.reason.required");
        }
        return tx.execute(status -> {
            ParcelService.Locked l = parcels.lock(parcelId);
            if (l.parcel().status() != ParcelStatus.VERIFICATION_LOCKED) {
                throw ParcelService.invalidState();
            }
            parcels.setStatus(parcelId, ParcelStatus.DELIVERY_PENDING);
            audit.record(adminId, "PARCEL_VERIFICATION_UNLOCKED", "PARCEL", parcelId, reasonCode,
                    "VERIFICATION_LOCKED", "DELIVERY_PENDING");
            ParcelService.Parcel after = parcels.find(parcelId).orElseThrow();
            parcels.emit("PARCEL_VERIFICATION_UNLOCKED", after, adminId, "VERIFICATION_LOCKED", "DELIVERY_PENDING", "SUPPORT_UNLOCK");
            return after;
        });
    }

    /** Материал проверки не живёт дольше часа после истечения (ТЗ разрешает до 24 часов). Вызывается по расписанию. */
    public int purgeStaleMaterial() {
        // Просроченная и не использованная проверка (ACTIVE) переходит в EXPIRED: у ACTIVE хеш обязан быть (ограничение базы).
        return jdbc.sql("""
                        UPDATE delivery_challenges
                        SET code_hash = NULL, status = CASE WHEN status = 'ACTIVE' THEN 'EXPIRED' ELSE status END
                        WHERE code_hash IS NOT NULL AND expires_at < :cutoff
                        """)
                .param("cutoff", utc(clock.instant().minusSeconds(3600)))
                .update();
    }

    // ---------- внутреннее ----------

    private String recipientPhone(UUID parcelId) {
        return jdbc.sql("SELECT recipient_phone FROM parcel_requests WHERE id = :id")
                .param("id", parcelId)
                .query(String.class)
                .single();
    }

    /** Привязка: проверка, посылка, номер получателя и переход входят в хеш, поэтому код нельзя применить в другом месте. */
    private String hash(UUID challengeId, UUID parcelId, String phone, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.pepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String data = "delivery-v1|" + challengeId + "|" + parcelId + "|" + phone + "|DELIVERY_PENDING>DELIVERED|" + code;
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC недоступен", e);
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private record Row(String status, String hash, int attempts, Instant expiresAt) {}
}
