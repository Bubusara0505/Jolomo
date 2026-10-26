package kg.jolomo.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.Test;

/** SP03-01: outbox переживает рестарт, ответ шлюза даёт только SUBMITTED, повтор безопасен. */
class SmsOutboxServiceTest extends PostgisTestBase {

    private final Clock clock = new MutableClock();
    private final AtomicInteger gatewayCalls = new AtomicInteger();
    private boolean gatewayAccepts = true;
    private final SmsGateway gateway = (cmd, phone, body) -> {
        gatewayCalls.incrementAndGet();
        return gatewayAccepts;
    };

    private SmsOutboxService service() {
        return new SmsOutboxService(jdbc, tx, gateway, new SmsBodyCipher(TEST_SMS_KEY), clock);
    }

    private String status(String cmd) {
        return jdbc.sql("SELECT status FROM sms_messages WHERE command_id = :c")
                .param("c", cmd)
                .query(String.class)
                .single();
    }

    @Test
    void sameCommandIdIsEnqueuedOnce() {
        assertThat(service().enqueue("cmd-1", "+996555000000", "hello")).isTrue();
        assertThat(service().enqueue("cmd-1", "+996555000000", "hello")).isFalse();

        assertThat(jdbc.sql("SELECT count(*) FROM sms_messages").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void queuedRowSurvivesRestartAndGatewayAcceptanceMeansSubmittedOnly() {
        service().enqueue("cmd-2", "+996555000000", "secret body");
        // «Рестарт»: новый экземпляр сервиса над той же БД видит ожидающую строку.
        SmsOutboxService afterRestart = service();
        assertThat(status("cmd-2")).isEqualTo("QUEUED");

        assertThat(afterRestart.dispatchPending(10)).isEqualTo(1);

        assertThat(status("cmd-2")).isEqualTo("SUBMITTED"); // не DELIVERED
        assertThat(jdbc.sql("SELECT body FROM sms_messages WHERE command_id = 'cmd-2'")
                        .query(String.class)
                        .optional())
                .isEmpty(); // текст (с возможным кодом) стёрт после отправки
    }

    @Test
    void bodyIsEncryptedAtRestAndGatewayGetsPlainText() {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        SmsOutboxService s = new SmsOutboxService(jdbc, tx, (c, p, b) -> {
            seen.set(b);
            return true;
        }, new SmsBodyCipher(TEST_SMS_KEY), clock);
        s.enqueue("cmd-enc", "+996555000000", "код 123456");

        String stored = jdbc.sql("SELECT body FROM sms_messages WHERE command_id = 'cmd-enc'")
                .query(String.class)
                .single();
        assertThat(stored).doesNotContain("123456");

        s.dispatchPending(10);
        assertThat(seen.get()).isEqualTo("код 123456");
    }

    @Test
    void repeatedAckAndRepeatedDispatchAreSafe() {
        SmsOutboxService s = service();
        s.enqueue("cmd-3", "+996555000000", "x");
        s.dispatchPending(10);

        assertThat(s.markSubmitted("cmd-3")).isFalse(); // повторный ack ничего не меняет
        assertThat(s.dispatchPending(10)).isZero();
        assertThat(gatewayCalls.get()).isEqualTo(1);
        assertThat(status("cmd-3")).isEqualTo("SUBMITTED");
    }

    @Test
    void rejectedMessageStaysQueuedThenFailsAfterMaxAttempts() {
        SmsOutboxService s = service();
        s.enqueue("cmd-4", "+996555000000", "x");
        gatewayAccepts = false;

        for (int i = 0; i < SmsOutboxService.MAX_ATTEMPTS - 1; i++) {
            s.dispatchPending(10);
            assertThat(status("cmd-4")).isEqualTo("QUEUED");
        }
        s.dispatchPending(10);

        assertThat(status("cmd-4")).isEqualTo("FAILED");
    }
}
