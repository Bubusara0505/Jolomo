package kg.jolomo.parcels;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.parcels.DeliveryService.VerifyOutcome;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP09-02 и SP09-03: один код, одна посылка, один получатель, один переход; три ошибки блокируют, но не открывают спор. */
class DeliveryVerifyTest extends PostgisTestBase {

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

    private VerifyOutcome verify(String c) {
        return d.delivery.verify(challenge, c);
    }

    @Test
    void rightCodeDeliversExactlyOnceAndLeavesEvidence() {
        assertThat(verify(code)).isEqualTo(VerifyOutcome.DELIVERED);

        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERED");
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE status = 'CONSUMED' AND code_hash IS NULL")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_DELIVERED' AND actor_user_id IS NULL "
                + "AND reason_code = 'RECIPIENT_CODE' AND from_state = 'DELIVERY_PENDING' AND to_state = 'DELIVERED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_DELIVERED'")).isEqualTo(1);
    }

    @Test
    void theSameCodeCannotBeUsedTwice() {
        verify(code);

        assertThat(verify(code)).isEqualTo(VerifyOutcome.ALREADY_USED);
        assertThat(verify("000000")).isEqualTo(VerifyOutcome.ALREADY_USED);
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_DELIVERED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_DELIVERED'")).isEqualTo(1);
    }

    @Test
    void wrongCodesAreCountedAndTheRightOneStillWorksBeforeTheThird() {
        String wrong = DeliveryFixture.wrongThan(code);

        assertThat(verify(wrong)).isEqualTo(VerifyOutcome.INVALID_CODE);
        assertThat(verify(wrong)).isEqualTo(VerifyOutcome.INVALID_CODE);
        assertThat(d.count("SELECT attempts_used FROM delivery_challenges WHERE status = 'ACTIVE'")).isEqualTo(2);
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERY_PENDING");

        assertThat(verify(code)).isEqualTo(VerifyOutcome.DELIVERED);
    }

    @Test
    void attemptsSurviveARestart() {
        String wrong = DeliveryFixture.wrongThan(code);
        verify(wrong);
        verify(wrong);

        // «Рестарт»: новый экземпляр сервиса на той же базе. Счётчик лежит в базе, а не в памяти.
        DeliveryFixture restarted = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);
        assertThat(restarted.delivery.verify(challenge, wrong)).isEqualTo(VerifyOutcome.LOCKED);
    }

    @Test
    void thirdWrongCodeLocksVerificationAndNeverOpensADispute() {
        String wrong = DeliveryFixture.wrongThan(code);
        verify(wrong);
        verify(wrong);

        assertThat(verify(wrong)).isEqualTo(VerifyOutcome.LOCKED);

        assertThat(d.parcelStatus(p.parcel())).isEqualTo("VERIFICATION_LOCKED");
        assertThat(d.count("SELECT count(*) FROM parcel_requests WHERE status = 'DISPUTED'")).isZero();
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'PARCEL_VERIFICATION_LOCKED' "
                + "AND reason_code = 'DELIVERY_CODE_ATTEMPTS'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type = 'PARCEL_VERIFICATION_LOCKED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE status = 'LOCKED' AND code_hash IS NULL AND attempts_used = 3"))
                .isEqualTo(1);

        // Даже верный код после блокировки ничего не доставляет.
        assertThat(verify(code)).isEqualTo(VerifyOutcome.LOCKED);
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("VERIFICATION_LOCKED");
    }

    @Test
    void expiredCodeIsRefusedAndTheParcelStaysPending() {
        d.f.clock.advance(Duration.ofMinutes(120).plusSeconds(1));

        assertThat(verify(code)).isEqualTo(VerifyOutcome.EXPIRED);
        assertThat(verify(code)).isEqualTo(VerifyOutcome.EXPIRED);
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERY_PENDING");
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE status = 'EXPIRED' AND code_hash IS NULL")).isEqualTo(1);
    }

    @Test
    void unknownOrForeignLinkIsAGenericNotFound() {
        assertThat(d.delivery.verify(UUID.randomUUID(), code)).isEqualTo(VerifyOutcome.NOT_FOUND);
        assertThat(d.delivery.verify(null, code)).isEqualTo(VerifyOutcome.NOT_FOUND);
        // id посылки вместо id проверки тоже не находится: ссылкой служит именно id проверки.
        assertThat(d.delivery.verify(p.parcel(), code)).isEqualTo(VerifyOutcome.NOT_FOUND);
        assertThat(d.delivery.view(UUID.randomUUID())).isEmpty();
    }

    @Test
    void missingCodeIsJustAWrongAttempt() {
        assertThat(verify(null)).isEqualTo(VerifyOutcome.INVALID_CODE);
        assertThat(verify("abc")).isEqualTo(VerifyOutcome.INVALID_CODE);
        assertThat(d.count("SELECT attempts_used FROM delivery_challenges WHERE status = 'ACTIVE'")).isEqualTo(2);
    }

    @Test
    void codeIsBoundToItsParcelAndRecipient() {
        // Перенос проверки на другую посылку: код не подходит.
        DeliveryFixture.Pending other = d.pending();
        jdbc.sql("UPDATE delivery_challenges SET parcel_id = :p WHERE id = :id").param("p", other.parcel()).param("id", challenge).update();
        assertThat(verify(code)).isEqualTo(VerifyOutcome.INVALID_CODE);
        jdbc.sql("UPDATE delivery_challenges SET parcel_id = :p WHERE id = :id").param("p", p.parcel()).param("id", challenge).update();
        jdbc.sql("UPDATE delivery_challenges SET attempts_used = 0 WHERE id = :id").param("id", challenge).update();

        // Подмена номера получателя: код, выданный на другой номер, не подходит.
        jdbc.sql("UPDATE parcel_requests SET recipient_phone = '+996700000002' WHERE id = :id").param("id", p.parcel()).update();
        assertThat(verify(code)).isEqualTo(VerifyOutcome.INVALID_CODE);
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERY_PENDING");
        assertThat(d.parcelStatus(other.parcel())).isEqualTo("DELIVERY_PENDING");
    }

    @Test
    void phoneLoginCodeIsAnotherSecretAndCannotCompleteDelivery() {
        // Отдельная таблица и отдельный секрет: идентификатор входа по телефону не открывает проверку доставки.
        UUID loginChallenge = UUID.randomUUID();
        jdbc.sql("INSERT INTO auth_challenges (id, phone, purpose, code_hash, expires_at, attempts_left, status, created_at) "
                        + "VALUES (:id, '+996700000001', 'LOGIN', 'x', now() + interval '5 minutes', 5, 'ACTIVE', now())")
                .param("id", loginChallenge)
                .update();

        assertThat(d.delivery.verify(loginChallenge, code)).isEqualTo(VerifyOutcome.NOT_FOUND);
        assertThat(d.parcelStatus(p.parcel())).isEqualTo("DELIVERY_PENDING");
    }

    @Test
    void viewShowsStateWithoutRevealingParcelDetails() {
        assertThat(d.delivery.view(challenge)).get().extracting(DeliveryService.ChallengeView::state).isEqualTo("ACTIVE");
        verify(code);
        assertThat(d.delivery.view(challenge)).get().extracting(DeliveryService.ChallengeView::state).isEqualTo("CONSUMED");
    }

    @Test
    void staleVerificationMaterialIsPurged() {
        d.f.clock.advance(Duration.ofMinutes(120).plusSeconds(3601));

        assertThat(d.delivery.purgeStaleMaterial()).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE code_hash IS NOT NULL")).isZero();
        // Просроченная и не использованная проверка закрыта, получатель увидит «истёк срок».
        assertThat(d.delivery.view(challenge)).get().extracting(DeliveryService.ChallengeView::state).isEqualTo("EXPIRED");
        assertThat(verify(code)).isEqualTo(VerifyOutcome.EXPIRED);
        assertThat(d.delivery.purgeStaleMaterial()).isZero();
    }
}
