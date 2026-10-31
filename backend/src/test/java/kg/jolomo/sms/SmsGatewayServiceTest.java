package kg.jolomo.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kg.jolomo.sms.SmsGatewayService.DeviceEvent;
import kg.jolomo.sms.SmsGatewayService.Leased;
import kg.jolomo.sms.SmsGatewayService.Outcome;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP10-01 (T-13): серверная часть Android-шлюза. Опрос ничего не доставляет, статус меняют только события устройства. */
class SmsGatewayServiceTest extends PostgisTestBase {

    private static final String D1 = "pixel-1";
    private static final String D2 = "pixel-2";

    private MutableClock clock;
    private SmsBodyCipher cipher;
    private SmsOutboxService outbox;
    private SmsGatewayService gateway;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE gateway_heartbeats").update();
        clock = new MutableClock();
        cipher = new SmsBodyCipher(Base64.getEncoder().encodeToString(new byte[32]));
        outbox = new SmsOutboxService(jdbc, tx, (c, p, b) -> true, cipher, clock);
        gateway = newGateway();
    }

    private SmsGatewayService newGateway() {
        return new SmsGatewayService(jdbc, tx, cipher, clock, new GatewaySettings(Duration.ofSeconds(60), Duration.ofMinutes(15)));
    }

    private String enqueue(String id) {
        outbox.enqueue(id, "+996555000001", "Код " + id);
        return id;
    }

    private String status(String id) {
        return jdbc.sql("SELECT status FROM sms_messages WHERE command_id = :c").param("c", id).query(String.class).single();
    }

    private String leasedBy(String id) {
        return jdbc.sql("SELECT leased_by FROM sms_messages WHERE command_id = :c").param("c", id).query(String.class).optional().orElse(null);
    }

    private boolean bodyErased(String id) {
        return jdbc.sql("SELECT body IS NULL FROM sms_messages WHERE command_id = :c").param("c", id).query(Boolean.class).single();
    }

    private Outcome send(String device, String id, String type) {
        return gateway.report(device, List.of(new DeviceEvent(id, type, null))).get(0).outcome();
    }

    // ---------- опрос ничего не доставляет ----------

    @Test
    void pollLeasesMessagesButNeverMarksAnythingSentOrDelivered() {
        enqueue("a");
        enqueue("b");
        enqueue("c");

        List<Leased> first = gateway.poll(D1, 10);
        List<Leased> repeat = gateway.poll(D1, 10);
        List<Leased> third = gateway.poll(D1, 10);

        assertThat(first).extracting(Leased::commandId).containsExactly("a", "b", "c");
        assertThat(first.get(0).phone()).isEqualTo("+996555000001");
        assertThat(first.get(0).body()).isEqualTo("Код a");
        assertThat(repeat).extracting(Leased::commandId).containsExactly("a", "b", "c");
        assertThat(third).hasSize(3);
        for (String id : List.of("a", "b", "c")) {
            assertThat(status(id)).isEqualTo("QUEUED");
            assertThat(leasedBy(id)).isEqualTo(D1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM sms_messages WHERE status IN ('SENT', 'DELIVERED')").query(Long.class).single()).isZero();
    }

    @Test
    void anotherDeviceDoesNotGetMessagesThatAreLeased() {
        enqueue("a");
        gateway.poll(D1, 10);

        assertThat(gateway.poll(D2, 10)).isEmpty();
    }

    @Test
    void anEmptyOrLatePollDoesNotChangeAnythingEvenForAcceptedMessages() {
        enqueue("a");
        gateway.poll(D1, 10);
        send(D1, "a", "ACCEPTED");
        clock.advance(Duration.ofHours(3));

        assertThat(gateway.poll(D1, 10)).isEmpty();
        assertThat(gateway.poll(D2, 10)).isEmpty();
        assertThat(status("a")).isEqualTo("SUBMITTED");
    }

    @Test
    void pollSizeIsCapped() {
        for (int i = 0; i < 60; i++) {
            enqueue("m" + i);
        }

        assertThat(gateway.poll(D1, 1000)).hasSize(SmsGatewayService.MAX_POLL);
        assertThat(gateway.poll(D2, 0)).hasSize(1);
    }

    // ---------- честный путь ----------

    @Test
    void acceptedSentDeliveredMovesForwardAndErasesTheBody() {
        enqueue("a");
        gateway.poll(D1, 10);

        assertThat(send(D1, "a", "ACCEPTED")).isEqualTo(Outcome.APPLIED);
        assertThat(status("a")).isEqualTo("SUBMITTED");
        assertThat(bodyErased("a")).isTrue();
        assertThat(gateway.poll(D1, 10)).isEmpty();

        assertThat(send(D1, "a", "SENT")).isEqualTo(Outcome.APPLIED);
        assertThat(status("a")).isEqualTo("SENT");
        assertThat(send(D1, "a", "DELIVERED")).isEqualTo(Outcome.APPLIED);
        assertThat(status("a")).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("SELECT delivered_at IS NOT NULL AND sent_at IS NOT NULL FROM sms_messages WHERE command_id = 'a'")
                        .query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void failureStoresAShortReasonCodeOnly() {
        enqueue("a");
        enqueue("b");
        gateway.poll(D1, 10);
        gateway.report(D1, List.of(new DeviceEvent("a", "FAILED", "NO_SERVICE"), new DeviceEvent("b", "FAILED", "очень длинный текст с телефоном +996700000001")));

        assertThat(status("a")).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failed_reason FROM sms_messages WHERE command_id = 'a'").query(String.class).single()).isEqualTo("NO_SERVICE");
        assertThat(jdbc.sql("SELECT failed_reason FROM sms_messages WHERE command_id = 'b'").query(String.class).single()).isEqualTo("UNSPECIFIED");
        assertThat(bodyErased("a")).isTrue();
    }

    // ---------- повторы и противоречия ----------

    @Test
    void repeatedEventsAreIgnoredAndNeverGoBackwards() {
        enqueue("a");
        gateway.poll(D1, 10);
        send(D1, "a", "ACCEPTED");
        send(D1, "a", "SENT");

        assertThat(send(D1, "a", "DELIVERED")).isEqualTo(Outcome.APPLIED);
        assertThat(send(D1, "a", "DELIVERED")).isEqualTo(Outcome.IGNORED);
        assertThat(send(D1, "a", "SENT")).isEqualTo(Outcome.IGNORED);
        assertThat(send(D1, "a", "ACCEPTED")).isEqualTo(Outcome.IGNORED);
        assertThat(status("a")).isEqualTo("DELIVERED");
    }

    @Test
    void contradictoryReportsAreRejected() {
        enqueue("failed");
        enqueue("delivered");
        gateway.poll(D1, 10);
        send(D1, "failed", "ACCEPTED");
        send(D1, "failed", "FAILED");
        send(D1, "delivered", "ACCEPTED");
        send(D1, "delivered", "DELIVERED");

        assertThat(send(D1, "failed", "DELIVERED")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D1, "failed", "SENT")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D1, "delivered", "FAILED")).isEqualTo(Outcome.REJECTED);
        assertThat(status("failed")).isEqualTo("FAILED");
        assertThat(status("delivered")).isEqualTo("DELIVERED");
    }

    // ---------- ложная доставка невозможна ----------

    @Test
    void deliveryCannotBeReportedForMessagesTheDeviceNeverAccepted() {
        enqueue("leased");
        enqueue("untouched");
        gateway.poll(D1, 1);   // закреплено только "leased"

        assertThat(send(D1, "leased", "DELIVERED")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D1, "leased", "SENT")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D1, "untouched", "DELIVERED")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D1, "untouched", "SENT")).isEqualTo(Outcome.REJECTED);
        assertThat(status("leased")).isEqualTo("QUEUED");
        assertThat(status("untouched")).isEqualTo("QUEUED");
    }

    @Test
    void anotherDeviceCannotReportOnSomeoneElsesMessageOrOnUnknownCommands() {
        enqueue("a");
        gateway.poll(D1, 10);
        send(D1, "a", "ACCEPTED");

        assertThat(send(D2, "a", "DELIVERED")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D2, "a", "FAILED")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D2, "a", "ACCEPTED")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D1, "no-such-command", "DELIVERED")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D1, "a", "SOMETHING_ELSE")).isEqualTo(Outcome.REJECTED);
        assertThat(status("a")).isEqualTo("SUBMITTED");
    }

    @Test
    void databaseItselfRefusesSentOrDeliveredWithoutADevice() {
        enqueue("a");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.sql("UPDATE sms_messages SET status = 'DELIVERED', delivered_at = now() WHERE command_id = 'a'").update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.sql("UPDATE sms_messages SET status = 'SENT' WHERE command_id = 'a'").update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(status("a")).isEqualTo("QUEUED");
    }

    // ---------- offline, задержка, рестарт ----------

    @Test
    void anOfflineDeviceYieldsUnknownNeverDeliveredAndALateGenuineReportResolvesIt() {
        enqueue("a");
        gateway.poll(D1, 10);
        send(D1, "a", "ACCEPTED");

        clock.advance(Duration.ofMinutes(10));
        assertThat(gateway.markSilentAsUnknown()).isZero();
        assertThat(status("a")).isEqualTo("SUBMITTED");

        clock.advance(Duration.ofMinutes(10));
        assertThat(gateway.markSilentAsUnknown()).isEqualTo(1);
        assertThat(status("a")).isEqualTo("UNKNOWN");

        // Опросы любых устройств UNKNOWN не меняют.
        gateway.poll(D1, 10);
        gateway.poll(D2, 10);
        assertThat(status("a")).isEqualTo("UNKNOWN");

        // Настоящий поздний отчёт самого устройства снимает неопределённость.
        assertThat(send(D1, "a", "DELIVERED")).isEqualTo(Outcome.APPLIED);
        assertThat(status("a")).isEqualTo("DELIVERED");
    }

    @Test
    void messagesWithoutADeviceFromTheFakeGatewayAreNeverTouchedByTheUnknownCheck() {
        enqueue("fake");
        outbox.dispatchPending(10);   // имитатор: SUBMITTED без устройства
        assertThat(status("fake")).isEqualTo("SUBMITTED");

        clock.advance(Duration.ofDays(1));

        assertThat(gateway.markSilentAsUnknown()).isZero();
        assertThat(status("fake")).isEqualTo("SUBMITTED");
    }

    @Test
    void stateAndIdempotencySurviveARestartOfTheServices() {
        enqueue("a");
        gateway.poll(D1, 10);
        send(D1, "a", "ACCEPTED");

        SmsGatewayService restarted = newGateway();
        assertThat(restarted.report(D1, List.of(new DeviceEvent("a", "SENT", null), new DeviceEvent("a", "DELIVERED", null))))
                .extracting(SmsGatewayService.EventResult::outcome).containsExactly(Outcome.APPLIED, Outcome.APPLIED);
        // Устройство не получило ответ и шлёт то же самое снова, теперь на старом экземпляре.
        assertThat(gateway.report(D1, List.of(new DeviceEvent("a", "DELIVERED", null))))
                .extracting(SmsGatewayService.EventResult::outcome).containsExactly(Outcome.IGNORED);
        assertThat(status("a")).isEqualTo("DELIVERED");
    }

    // ---------- аренда и сроки ----------

    @Test
    void aLostPollResponseDoesNotLoseTheMessage() {
        enqueue("a");
        gateway.poll(D1, 10);          // ответ потерялся, подтверждения нет
        clock.advance(Duration.ofSeconds(61));

        List<Leased> again = gateway.poll(D2, 10);

        assertThat(again).extracting(Leased::commandId).containsExactly("a");
        assertThat(leasedBy("a")).isEqualTo(D2);
        assertThat(jdbc.sql("SELECT attempts FROM sms_messages WHERE command_id = 'a'").query(Integer.class).single()).isEqualTo(1);
        // Запоздавшее подтверждение прежнего устройства отклоняется, у сообщения уже другой владелец.
        assertThat(send(D1, "a", "ACCEPTED")).isEqualTo(Outcome.REJECTED);
        assertThat(send(D2, "a", "ACCEPTED")).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void aSameDevicePollingAgainDoesNotExtendTheLeaseForever() {
        enqueue("a");
        gateway.poll(D1, 10);
        clock.advance(Duration.ofSeconds(40));
        gateway.poll(D1, 10);
        clock.advance(Duration.ofSeconds(30));   // с первого опроса 70 с: аренда истекла

        assertThat(gateway.poll(D2, 10)).extracting(Leased::commandId).containsExactly("a");
    }

    @Test
    void afterFiveLeasesWithoutAckTheMessageFailsAndItsBodyIsErased() {
        enqueue("a");
        for (int i = 0; i < 5; i++) {
            assertThat(gateway.poll(D1, 10)).hasSize(1);
            clock.advance(Duration.ofSeconds(61));
        }

        assertThat(gateway.poll(D1, 10)).isEmpty();

        assertThat(status("a")).isEqualTo("FAILED");
        assertThat(bodyErased("a")).isTrue();
        assertThat(jdbc.sql("SELECT failed_reason FROM sms_messages WHERE command_id = 'a'").query(String.class).single())
                .isEqualTo("GATEWAY_NO_ACK");
    }

    @Test
    void anExpiredMessageIsNeverHandedToTheDevice() {
        outbox.enqueue("otp", "+996555000001", "Код otp", clock.instant().plusSeconds(10));
        clock.advance(Duration.ofSeconds(11));

        assertThat(gateway.poll(D1, 10)).isEmpty();

        assertThat(status("otp")).isEqualTo("FAILED");
        assertThat(bodyErased("otp")).isTrue();
    }

    // ---------- параллельность и сердцебиение ----------

    @Test
    void messagesAreNeverHandedToTwoDevicesAtOnce() throws Exception {
        for (int i = 0; i < 20; i++) {
            enqueue("m" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<Leased>>> futures = new ArrayList<>();
            for (int d = 0; d < 4; d++) {
                String device = "dev-" + d;
                futures.add(pool.submit(() -> {
                    start.await();
                    return gateway.poll(device, 50);
                }));
            }
            start.countDown();
            Set<String> seen = new HashSet<>();
            int total = 0;
            for (Future<List<Leased>> f : futures) {
                for (Leased l : f.get()) {
                    seen.add(l.commandId());
                    total++;
                }
            }
            assertThat(total).isEqualTo(20);
            assertThat(seen).hasSize(20);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void heartbeatRecordsWhenTheDeviceWasLastSeen() {
        assertThat(gateway.lastSeen(D1)).isEmpty();
        assertThat(gateway.lastSeenAny()).isEmpty();
        Instant t0 = clock.instant();
        gateway.poll(D1, 1);
        clock.advance(Duration.ofMinutes(5));
        gateway.poll(D2, 1);

        assertThat(gateway.lastSeen(D1)).contains(t0);
        assertThat(gateway.lastSeen(D2)).contains(t0.plus(Duration.ofMinutes(5)));
        assertThat(gateway.lastSeenAny()).contains(t0.plus(Duration.ofMinutes(5)));
    }
}
