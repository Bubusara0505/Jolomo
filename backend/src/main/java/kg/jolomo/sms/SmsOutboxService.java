package kg.jolomo.sms;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Долговечный outbox. Ожидающие строки живут в БД и переживают рестарт.
 * Текст хранится зашифрованным и стирается, когда сообщение ушло или не может уйти.
 * Ответ шлюза «принято» даёт только SUBMITTED, а не DELIVERED.
 *
 * <p>Известное ограничение: вызов шлюза идёт внутри транзакции. Если процесс упадёт после «принято»
 * и до фиксации, сообщение уйдёт повторно, поэтому настоящий шлюз обязан дедуплицировать по command id
 * (задача SP10-01).
 */
@Service
public class SmsOutboxService {

    static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final SmsGateway gateway;
    private final SmsBodyCipher cipher;
    private final Clock clock;

    public SmsOutboxService(
            JdbcClient jdbc, TransactionOperations tx, SmsGateway gateway, SmsBodyCipher cipher, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gateway = gateway;
        this.cipher = cipher;
        this.clock = clock;
    }

    /** @return true, если строка создана; false, если такой command id уже был (повтор безопасен). */
    public boolean enqueue(String commandId, String phone, String body) {
        return enqueue(commandId, phone, body, null);
    }

    /**
     * @param expiresAt после этого момента сообщение не отправляется, текст стирается (null: без срока)
     */
    public boolean enqueue(String commandId, String phone, String body, Instant expiresAt) {
        int inserted = jdbc.sql("""
                        INSERT INTO sms_messages (id, command_id, phone, body, status, created_at, expires_at)
                        VALUES (:id, :cmd, :phone, :body, 'QUEUED', :now, :exp)
                        ON CONFLICT (command_id) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("cmd", commandId)
                .param("phone", phone)
                .param("body", cipher.encrypt(body, commandId))
                .param("now", now())
                .param("exp", expiresAt == null ? null : OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    /** Отдаёт шлюзу ожидающие сообщения. @return сколько принято шлюзом. */
    public int dispatchPending(int limit) {
        Integer accepted = tx.execute(status -> {
            expireStale();
            List<Pending> batch = jdbc.sql("""
                            SELECT command_id, phone, body FROM sms_messages
                            WHERE status = 'QUEUED'
                            ORDER BY created_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", limit)
                    .query((rs, i) -> new Pending(rs.getString(1), rs.getString(2), rs.getString(3)))
                    .list();
            int ok = 0;
            for (Pending p : batch) {
                boolean submitted;
                try {
                    submitted = gateway.submit(p.commandId(), p.phone(), cipher.decrypt(p.body(), p.commandId()));
                } catch (RuntimeException e) {
                    submitted = false;
                }
                if (submitted) {
                    markSubmitted(p.commandId());
                    ok++;
                } else {
                    recordFailure(p.commandId());
                }
            }
            return ok;
        });
        return accepted == null ? 0 : accepted;
    }

    /** Просроченное сообщение (например, OTP, код которого уже истёк) не отправляем и стираем. */
    private void expireStale() {
        jdbc.sql("""
                        UPDATE sms_messages SET status = 'FAILED', body = NULL
                        WHERE status = 'QUEUED' AND expires_at IS NOT NULL AND expires_at <= :now
                        """)
                .param("now", now())
                .update();
    }

    /** Идемпотентно: повторный ack того же command id ничего не меняет. */
    public boolean markSubmitted(String commandId) {
        return jdbc.sql("""
                        UPDATE sms_messages
                        SET status = 'SUBMITTED', body = NULL, submitted_at = :now
                        WHERE command_id = :cmd AND status = 'QUEUED'
                        """)
                        .param("cmd", commandId)
                        .param("now", now())
                        .update()
                == 1;
    }

    private void recordFailure(String commandId) {
        jdbc.sql("""
                        UPDATE sms_messages
                        SET attempts = attempts + 1,
                            status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE status END,
                            body = CASE WHEN attempts + 1 >= :max THEN NULL ELSE body END
                        WHERE command_id = :cmd AND status = 'QUEUED'
                        """)
                .param("max", MAX_ATTEMPTS)
                .param("cmd", commandId)
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private record Pending(String commandId, String phone, String body) {}
}
