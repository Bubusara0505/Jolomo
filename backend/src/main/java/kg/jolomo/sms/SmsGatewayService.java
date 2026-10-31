package kg.jolomo.sms;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Серверная часть Android-шлюза SMS (SP10-01, T-13). Правда о статусе SMS:
 * <ul>
 *   <li>Опрос ничего не доставляет. poll только закрепляет сообщения за устройством и отмечает, что оно живо.
 *       Поздний, повторный или пустой опрос статус сообщения не меняет.</li>
 *   <li>Статус меняют только явные события устройства, которое держит сообщение: ACCEPTED (шлюз принял, это SUBMITTED),
 *       SENT (ушло в сеть), DELIVERED (отчёт о доставке от сети), FAILED. Событие чужого или незакреплённого сообщения
 *       отклоняется. DELIVERED возможен только после ACCEPTED, и база не допустит SENT или DELIVERED без устройства.</li>
 *   <li>Повторное событие безопасно (IGNORED), статус не откатывается назад, противоречие (доставлено после FAILED
 *       и наоборот) отклоняется.</li>
 *   <li>Принято, но отчётов нет дольше срока: UNKNOWN. Сервер не гадает ни «доставлено», ни «не доставлено».
 *       Поздний настоящий отчёт устройства снимает UNKNOWN.</li>
 *   <li>Сообщение без подтверждения возвращается в очередь после срока аренды (до пяти попыток), поэтому потеря ответа
 *       на опрос не теряет SMS. Устройство обязано дедуплицировать по command id.</li>
 * </ul>
 * Тексты SMS расшифровываются только в момент выдачи устройству и после принятия стираются из базы.
 */
@Service
@ConditionalOnProperty(name = "jolomo.sms.gateway", havingValue = "android")
public class SmsGatewayService {

    /** Сообщение для отправки устройством. */
    public record Leased(String commandId, String phone, String body) {}

    /** Событие от устройства: тип ACCEPTED, SENT, DELIVERED или FAILED, для FAILED короткий код причины. */
    public record DeviceEvent(String commandId, String type, String reason) {}

    public enum Outcome { APPLIED, IGNORED, REJECTED }

    public record EventResult(String commandId, Outcome outcome) {}

    public static final List<String> EVENT_TYPES = List.of("ACCEPTED", "SENT", "DELIVERED", "FAILED");

    static final int MAX_POLL = 50;
    private static final Pattern REASON = Pattern.compile("[A-Z0-9_]{2,40}");

    private record Row(String status, String leasedBy) {}

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final SmsBodyCipher cipher;
    private final Clock clock;
    private final GatewaySettings settings;

    public SmsGatewayService(JdbcClient jdbc, TransactionOperations tx, SmsBodyCipher cipher, Clock clock,
                             GatewaySettings settings) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.cipher = cipher;
        this.clock = clock;
        this.settings = settings;
    }

    /** Опрос: отмечает живое устройство и закрепляет за ним сообщения. Статусы сообщений не меняет. */
    public List<Leased> poll(String device, int limit) {
        int n = Math.max(1, Math.min(limit, MAX_POLL));
        List<Leased> result = tx.execute(status -> {
            OffsetDateTime now = now();
            jdbc.sql("""
                            INSERT INTO gateway_heartbeats (device, last_seen_at, polls) VALUES (:d, :now, 1)
                            ON CONFLICT (device) DO UPDATE SET last_seen_at = :now, polls = gateway_heartbeats.polls + 1
                            """)
                    .param("d", device)
                    .param("now", now)
                    .update();
            // Просроченное (например, OTP, срок кода которого вышел) не отправляем и стираем.
            jdbc.sql("""
                            UPDATE sms_messages SET status = 'FAILED', body = NULL, failed_reason = 'EXPIRED',
                                   leased_by = NULL, lease_until = NULL
                            WHERE status = 'QUEUED' AND expires_at IS NOT NULL AND expires_at <= :now
                            """)
                    .param("now", now)
                    .update();
            // Аренда без подтверждения истекла: сообщение возвращается в очередь, после пяти попыток завершается.
            jdbc.sql("""
                            UPDATE sms_messages SET attempts = attempts + 1,
                                   status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE status END,
                                   body = CASE WHEN attempts + 1 >= :max THEN NULL ELSE body END,
                                   failed_reason = CASE WHEN attempts + 1 >= :max THEN 'GATEWAY_NO_ACK' ELSE failed_reason END,
                                   leased_by = NULL, lease_until = NULL
                            WHERE status = 'QUEUED' AND lease_until IS NOT NULL AND lease_until <= :now
                            """)
                    .param("max", SmsOutboxService.MAX_ATTEMPTS)
                    .param("now", now)
                    .update();
            List<String[]> rows = jdbc.sql("""
                            SELECT command_id, phone, body FROM sms_messages
                            WHERE status = 'QUEUED' AND body IS NOT NULL AND (lease_until IS NULL OR leased_by = :d)
                            ORDER BY created_at
                            LIMIT :n
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("d", device)
                    .param("n", n)
                    .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)})
                    .list();
            OffsetDateTime until = now.plus(settings.lease());
            List<Leased> leased = new ArrayList<>();
            for (String[] r : rows) {
                // Повторный опрос того же устройства отдаёт те же сообщения и срок аренды не продлевает.
                jdbc.sql("""
                                UPDATE sms_messages SET leased_by = :d,
                                       lease_until = CASE WHEN leased_by = :d THEN lease_until ELSE :until END
                                WHERE command_id = :c
                                """)
                        .param("d", device)
                        .param("until", until)
                        .param("c", r[0])
                        .update();
                leased.add(new Leased(r[0], r[1], cipher.decrypt(r[2], r[0])));
            }
            return leased;
        });
        return result == null ? List.of() : result;
    }

    /** События от устройства. Результат по каждому: APPLIED, IGNORED (повтор) или REJECTED. */
    public List<EventResult> report(String device, List<DeviceEvent> events) {
        List<EventResult> results = tx.execute(status -> {
            OffsetDateTime now = now();
            List<EventResult> out = new ArrayList<>();
            for (DeviceEvent e : events) {
                out.add(new EventResult(e.commandId(), apply(device, e, now)));
            }
            return out;
        });
        return results == null ? List.of() : results;
    }

    /**
     * Принятое устройством сообщение, по которому дольше срока нет ни одного отчёта, становится UNKNOWN. Сообщения
     * без устройства (имитатор шлюза, статус SUBMITTED) не трогаем. @return сколько сообщений переведено.
     */
    public int markSilentAsUnknown() {
        OffsetDateTime threshold = now().minus(settings.unknownAfter());
        return jdbc.sql("""
                        UPDATE sms_messages SET status = 'UNKNOWN'
                        WHERE status = 'SUBMITTED' AND leased_by IS NOT NULL AND submitted_at <= :threshold
                        """)
                .param("threshold", threshold)
                .update();
    }

    /** Когда устройство в последний раз опрашивало сервер. */
    public Optional<Instant> lastSeen(String device) {
        return jdbc.sql("SELECT last_seen_at FROM gateway_heartbeats WHERE device = :d")
                .param("d", device)
                .query((rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional();
    }

    /** Самый свежий опрос любого устройства (для алерта «молчание шлюза»). */
    public Optional<Instant> lastSeenAny() {
        return jdbc.sql("SELECT last_seen_at FROM gateway_heartbeats ORDER BY last_seen_at DESC LIMIT 1")
                .query((rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .optional();
    }

    // ---------- переходы ----------

    private Outcome apply(String device, DeviceEvent e, OffsetDateTime now) {
        Row row = jdbc.sql("SELECT status, leased_by FROM sms_messages WHERE command_id = :c FOR UPDATE")
                .param("c", e.commandId())
                .query((rs, i) -> new Row(rs.getString(1), rs.getString(2)))
                .optional()
                .orElse(null);
        if (row == null || e.type() == null) {
            return Outcome.REJECTED;
        }
        boolean mine = device.equals(row.leasedBy());
        String st = row.status();
        switch (e.type()) {
            case "ACCEPTED":
                if ("QUEUED".equals(st) && (mine || row.leasedBy() == null)) {
                    update(e.commandId(), "status = 'SUBMITTED', body = NULL, leased_by = :d, lease_until = NULL, submitted_at = :now", device, now, null);
                    return Outcome.APPLIED;
                }
                return mine && in(st, "SUBMITTED", "SENT", "DELIVERED", "UNKNOWN") ? Outcome.IGNORED : Outcome.REJECTED;
            case "SENT":
                if (!mine) {
                    return Outcome.REJECTED;
                }
                if (in(st, "SUBMITTED", "UNKNOWN")) {
                    update(e.commandId(), "status = 'SENT', sent_at = :now", device, now, null);
                    return Outcome.APPLIED;
                }
                return in(st, "SENT", "DELIVERED") ? Outcome.IGNORED : Outcome.REJECTED;
            case "DELIVERED":
                if (!mine) {
                    return Outcome.REJECTED;
                }
                if (in(st, "SUBMITTED", "SENT", "UNKNOWN")) {
                    update(e.commandId(), "status = 'DELIVERED', delivered_at = :now, sent_at = COALESCE(sent_at, :now)", device, now, null);
                    return Outcome.APPLIED;
                }
                return "DELIVERED".equals(st) ? Outcome.IGNORED : Outcome.REJECTED;
            case "FAILED":
                if (!mine) {
                    return Outcome.REJECTED;
                }
                if (in(st, "QUEUED", "SUBMITTED", "SENT", "UNKNOWN")) {
                    String reason = e.reason() != null && REASON.matcher(e.reason()).matches() ? e.reason() : "UNSPECIFIED";
                    update(e.commandId(), "status = 'FAILED', body = NULL, lease_until = NULL, failed_reason = :reason", device, now, reason);
                    return Outcome.APPLIED;
                }
                return "FAILED".equals(st) ? Outcome.IGNORED : Outcome.REJECTED;
            default:
                return Outcome.REJECTED;
        }
    }

    private void update(String commandId, String set, String device, OffsetDateTime now, String reason) {
        JdbcClient.StatementSpec spec = jdbc.sql("UPDATE sms_messages SET " + set + " WHERE command_id = :c")
                .param("c", commandId)
                .param("now", now);
        if (set.contains(":d")) {
            spec = spec.param("d", device);
        }
        if (set.contains(":reason")) {
            spec = spec.param("reason", reason);
        }
        spec.update();
    }

    private static boolean in(String value, String... options) {
        for (String o : options) {
            if (o.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
