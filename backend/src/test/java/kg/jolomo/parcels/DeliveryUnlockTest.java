package kg.jolomo.parcels;

import static kg.jolomo.parcels.ParcelFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.parcels.DeliveryService.VerifyOutcome;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP09-03: узкий путь снятия блокировки: только поддержка, с кодом причины и аудитом, без доставки. */
class DeliveryUnlockTest extends PostgisTestBase {

    private static final UUID SUPPORT = UUID.randomUUID();

    private DeliveryFixture d;
    private DeliveryFixture.Pending p;

    @BeforeEach
    void setUp() {
        d = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);
        p = d.pending();
        String[] ch = d.issue(p);
        String wrong = DeliveryFixture.wrongThan(ch[1]);
        for (int i = 0; i < 3; i++) {
            d.delivery.verify(UUID.fromString(ch[0]), wrong);
        }
    }

    @Test
    void unlockReturnsTheParcelToPendingAndIsAudited() {
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("VERIFICATION_LOCKED");

        d.delivery.unlockVerification(SUPPORT, p.parcel(), "RECIPIENT_CONFIRMED_BY_PHONE");

        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERY_PENDING");
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_VERIFICATION_UNLOCKED' "
                + "AND reason_code = 'RECIPIENT_CONFIRMED_BY_PHONE' AND actor_user_id IS NOT NULL")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_VERIFICATION_UNLOCKED'")).isEqualTo(1);
    }

    @Test
    void unlockNeverDeliversAndTheOldLockedCodeStaysDead() {
        UUID old = jdbc.sql("SELECT id FROM delivery_challenges WHERE status = 'LOCKED'").query(UUID.class).single();
        d.delivery.unlockVerification(SUPPORT, p.parcel(), "RECIPIENT_CONFIRMED_BY_PHONE");

        assertThat(d.parcelStatus(p.parcel())).isNotEqualTo("DELIVERED");
        assertThat(d.delivery.verify(old, "123456")).isEqualTo(VerifyOutcome.LOCKED);
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_DELIVERED'")).isZero();
    }

    @Test
    void afterUnlockTheDriverGetsANewCodeWithAFreshBudgetAndDeliveryWorks() {
        d.delivery.unlockVerification(SUPPORT, p.parcel(), "RECIPIENT_CONFIRMED_BY_PHONE");
        d.f.clock.advance(Duration.ofSeconds(61));

        String[] fresh = d.issue(p);

        assertThat(d.delivery.verify(UUID.fromString(fresh[0]), DeliveryFixture.wrongThan(fresh[1]))).isEqualTo(VerifyOutcome.INVALID_CODE);
        assertThat(d.delivery.verify(UUID.fromString(fresh[0]), fresh[1])).isEqualTo(VerifyOutcome.DELIVERED);
    }

    @Test
    void unlockNeedsALockedParcelAndAReasonCode() {
        expectCode(() -> d.delivery.unlockVerification(SUPPORT, p.parcel(), null), "REASON_REQUIRED");
        expectCode(() -> d.delivery.unlockVerification(SUPPORT, p.parcel(), "no reason"), "REASON_REQUIRED");
        expectCode(() -> d.delivery.unlockVerification(SUPPORT, UUID.randomUUID(), "RECIPIENT_CONFIRMED_BY_PHONE"), "PARCEL_NOT_FOUND");
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("VERIFICATION_LOCKED");

        DeliveryFixture.Pending fine = d.pending();
        expectCode(() -> d.delivery.unlockVerification(SUPPORT, fine.parcel(), "RECIPIENT_CONFIRMED_BY_PHONE"), "INVALID_PARCEL_STATE");
    }
}
