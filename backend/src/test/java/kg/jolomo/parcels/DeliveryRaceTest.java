package kg.jolomo.parcels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kg.jolomo.parcels.DeliveryService.VerifyOutcome;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP09-02, SP09-03 и основа SP10-04 на настоящем PostGIS: параллельные проверки дают одну доставку и одну блокировку. */
class DeliveryRaceTest extends PostgisTestBase {

    private static final int THREADS = 8;

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

    private List<VerifyOutcome> raceVerify(String c) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<VerifyOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return d.delivery.verify(challenge, c);
                }));
            }
            start.countDown();
            List<VerifyOutcome> out = new ArrayList<>();
            for (Future<VerifyOutcome> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void eightParallelRightCodesDeliverExactlyOnce() throws Exception {
        List<VerifyOutcome> outcomes = raceVerify(code);

        assertThat(outcomes).filteredOn(o -> o == VerifyOutcome.DELIVERED).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == VerifyOutcome.ALREADY_USED).hasSize(THREADS - 1);
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERED");
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_DELIVERED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_DELIVERED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE status = 'CONSUMED'")).isEqualTo(1);
    }

    @Test
    void eightParallelWrongCodesLockExactlyOnceAfterExactlyThreeAttempts() throws Exception {
        List<VerifyOutcome> outcomes = raceVerify(DeliveryFixture.wrongThan(code));

        assertThat(outcomes).filteredOn(o -> o == VerifyOutcome.INVALID_CODE).hasSize(2);
        assertThat(outcomes).filteredOn(o -> o == VerifyOutcome.LOCKED).hasSize(THREADS - 2);
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("VERIFICATION_LOCKED");
        assertThat(d.count("SELECT attempts_used FROM delivery_challenges WHERE id = '" + challenge + "'")).isEqualTo(3);
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_VERIFICATION_LOCKED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_VERIFICATION_LOCKED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM parcel_requests WHERE status = 'DISPUTED'")).isZero();
    }

    @Test
    void rightAndWrongCodesRacingNeverProduceAnInconsistentParcel() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<VerifyOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String attempt = i == 0 ? code : DeliveryFixture.wrongThan(code);
                futures.add(pool.submit(() -> {
                    start.await();
                    return d.delivery.verify(challenge, attempt);
                }));
            }
            start.countDown();
            for (Future<VerifyOutcome> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        String status = d.parcelStatus(p.parcel());
        // Верный код либо успел раньше третьей ошибки (DELIVERED), либо опоздал (VERIFICATION_LOCKED). Третьего не дано.
        assertThat(status).isIn("DELIVERED", "VERIFICATION_LOCKED");
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type IN ('PARCEL_DELIVERED', 'PARCEL_VERIFICATION_LOCKED')"))
                .isEqualTo(1);
    }
}
