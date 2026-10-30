package kg.jolomo.parcels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import kg.jolomo.parcels.DeliveryService.VerifyOutcome;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * SP10-04: сбой между проверкой кода и фиксацией транзакции не оставляет ни двух DELIVERED, ни проверки без статуса.
 * Сбой имитируется отказом журнала событий на последнем шаге транзакции доставки: всё, что было записано раньше
 * (код потреблён, статус, аудит), должно откатиться, а повтор должен довести доставку ровно один раз.
 */
class DeliveryAtomicityTest extends PostgisTestBase {

    private DeliveryFixture d;
    private DeliveryFixture.Pending p;
    private UUID challenge;
    private String code;

    @BeforeEach
    void setUp() {
        d = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);
        p = d.pending();
        String[] ch = d.issue(p);
        challenge = UUID.fromString(ch[0]);
        code = ch[1];
    }

    private void assertNothingHalfDone() {
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERY_PENDING");
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE id = '" + challenge
                + "' AND status = 'ACTIVE' AND code_hash IS NOT NULL AND attempts_used = 0 AND consumed_at IS NULL")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_DELIVERED'")).isZero();
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_DELIVERED'")).isZero();
    }

    @Test
    void crashAfterTheCodeWasConsumedRollsEverythingBackAndRetryDeliversOnce() {
        d.f.events.failOnType = "PARCEL_DELIVERED";

        assertThatThrownBy(() -> d.delivery.verify(challenge, code))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("simulated crash");

        assertNothingHalfDone(); // ни потреблённого кода без статуса, ни статуса без кода

        d.f.events.failOnType = null;
        assertThat(d.delivery.verify(challenge, code)).isEqualTo(VerifyOutcome.DELIVERED);
        assertThat(d.delivery.verify(challenge, code)).isEqualTo(VerifyOutcome.ALREADY_USED);
        assertThat(d.delivery.verify(challenge, code)).isEqualTo(VerifyOutcome.ALREADY_USED);

        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERED");
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_DELIVERED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_DELIVERED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE status = 'CONSUMED'")).isEqualTo(1);
    }

    @Test
    void aRestartedServiceFinishesWhatACrashLeftUndone() {
        d.f.events.failOnType = "PARCEL_DELIVERED";
        assertThatThrownBy(() -> d.delivery.verify(challenge, code)).isInstanceOf(IllegalStateException.class);

        // «Рестарт»: новый набор сервисов на той же базе, без сбоя. Код из SMS остался прежним и всё ещё действует.
        DeliveryFixture restarted = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);

        assertThat(restarted.delivery.verify(challenge, code)).isEqualTo(VerifyOutcome.DELIVERED);
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_DELIVERED'")).isEqualTo(1);
    }

    @Test
    void crashWhileLockingNeitherLosesNorDoubleCountsTheAttempt() {
        String wrong = DeliveryFixture.wrongThan(code);
        d.delivery.verify(challenge, wrong);
        d.delivery.verify(challenge, wrong);
        d.f.events.failOnType = "PARCEL_VERIFICATION_LOCKED";

        assertThatThrownBy(() -> d.delivery.verify(challenge, wrong)).isInstanceOf(IllegalStateException.class);

        // Третья ошибка не засчитана и не заблокировала половину: счётчик по-прежнему два, посылка ждёт.
        assertThat(d.count("SELECT attempts_used FROM delivery_challenges WHERE id = '" + challenge + "'")).isEqualTo(2);
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERY_PENDING");
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_VERIFICATION_LOCKED'")).isZero();

        d.f.events.failOnType = null;
        assertThat(d.delivery.verify(challenge, wrong)).isEqualTo(VerifyOutcome.LOCKED);
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_VERIFICATION_LOCKED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM parcel_requests WHERE status = 'DISPUTED'")).isZero();
    }

    @Test
    void theDatabaseItselfRefusesASecondDeliveredEvent() {
        d.delivery.verify(challenge, code);

        assertThatThrownBy(() -> d.f.events.append("PARCEL", p.parcel(), "PARCEL_DELIVERED", null, Map.of()))
                .isInstanceOf(DataAccessException.class);
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_DELIVERED'")).isEqualTo(1);
    }

    @Test
    void aSecondChallengeCannotBeConsumedForTheSameParcel() {
        d.delivery.verify(challenge, code);

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO delivery_challenges (id, parcel_id, status, created_at, expires_at, consumed_at)
                                VALUES (:id, :p, 'CONSUMED', now(), now() + interval '1 hour', now())
                                """)
                        .param("id", UUID.randomUUID())
                        .param("p", p.parcel())
                        .update())
                .isInstanceOf(DataAccessException.class);
    }
}
