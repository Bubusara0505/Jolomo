package kg.jolomo.parcels;

import static kg.jolomo.parcels.ParcelFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP09-02: водитель просит код, код уходит получателю по SMS и нигде больше не появляется. */
class DeliveryChallengeTest extends PostgisTestBase {

    private DeliveryFixture d;
    private DeliveryFixture.Pending p;

    @BeforeEach
    void setUp() {
        d = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);
        p = d.pending();
    }

    @Test
    void codeGoesOnlyToTheRecipientBySmsAndIsStoredAsAHash() {
        DeliveryService.Issued issued = d.delivery.requestChallenge(p.driver(), p.parcel());

        UUID id = d.activeChallenge(p.parcel());
        String code = d.codeOf(id);
        assertThat(code).matches("\\d{6}");
        assertThat(issued.expiresAt()).isEqualTo(d.f.clock.instant().plus(Duration.ofMinutes(120)));
        assertThat(issued.toString()).doesNotContain(code);
        assertThat(jdbc.sql("SELECT phone FROM sms_messages WHERE command_id = :c").param("c", "delivery:" + id)
                        .query(String.class).single())
                .isEqualTo(DeliveryFixture.RECIPIENT_PHONE);
        String stored = jdbc.sql("SELECT code_hash FROM delivery_challenges WHERE id = :id").param("id", id)
                .query(String.class).single();
        assertThat(stored).hasSize(64).isNotEqualTo(code);
        String body = jdbc.sql("SELECT body FROM sms_messages WHERE command_id = :c").param("c", "delivery:" + id)
                .query(String.class).single();
        assertThat(body).doesNotContain(code).doesNotContain(id.toString());
    }

    @Test
    void codeIsNowhereInAuditEventsOrOutboxPlainText() {
        String[] ch = d.issue(p);
        d.delivery.verify(UUID.fromString(ch[0]), ch[1]);

        String audit = jdbc.sql("SELECT coalesce(string_agg(concat_ws(' ', action, subject_type, reason_code, from_state, to_state), ' '), '') FROM audit_events")
                .query(String.class).single();
        String events = jdbc.sql("SELECT coalesce(string_agg(payload::text || event_type, ' '), '') FROM domain_events")
                .query(String.class).single();
        assertThat(audit).doesNotContain(ch[1]);
        assertThat(events).doesNotContain(ch[1]).doesNotContain(DeliveryFixture.RECIPIENT_PHONE);
        assertThat(d.count("SELECT count(*) FROM sms_messages WHERE body LIKE '%" + ch[1] + "%'")).isZero();
    }

    @Test
    void onlyWhileTheDriverIsWaitingForTheRecipient() {
        DeliveryFixture.Pending moving = d.inTransit();

        expectCode(() -> d.delivery.requestChallenge(moving.driver(), moving.parcel()), "INVALID_PARCEL_STATE");
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE parcel_id = '" + moving.parcel() + "'")).isZero();
    }

    @Test
    void onlyTheAssignedDriverMayAsk() {
        expectCode(() -> d.delivery.requestChallenge(p.sender(), p.parcel()), "PARCEL_NOT_FOUND");
        expectCode(() -> d.delivery.requestChallenge(d.f.driver(), p.parcel()), "PARCEL_NOT_FOUND");
        expectCode(() -> d.delivery.requestChallenge(p.driver(), UUID.randomUUID()), "PARCEL_NOT_FOUND");
    }

    @Test
    void suspendedDriverCannotAsk() {
        d.f.profiles.suspend(p.driver(), ParcelFixture.ADMIN, "SUSPICIOUS_ACTIVITY");

        expectCode(() -> d.delivery.requestChallenge(p.driver(), p.parcel()), "NOT_APPROVED");
    }

    @Test
    void newRequestReplacesTheOldCodeAndResendIsLimited() {
        d.delivery.requestChallenge(p.driver(), p.parcel());
        UUID first = d.activeChallenge(p.parcel());
        expectCode(() -> d.delivery.requestChallenge(p.driver(), p.parcel()), "DELIVERY_TOO_SOON");

        d.f.clock.advance(Duration.ofSeconds(61));
        d.delivery.requestChallenge(p.driver(), p.parcel());

        assertThat(d.delivery.verify(first, "123456")).isEqualTo(DeliveryService.VerifyOutcome.NOT_FOUND);
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE status = 'SUPERSEDED'")).isEqualTo(1);
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE status = 'ACTIVE'")).isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            d.f.clock.advance(Duration.ofSeconds(61));
            d.delivery.requestChallenge(p.driver(), p.parcel());
        }
        d.f.clock.advance(Duration.ofSeconds(61));
        expectCode(() -> d.delivery.requestChallenge(p.driver(), p.parcel()), "DELIVERY_RESEND_LIMIT");
    }

    @Test
    void lockedParcelGetsNoNewCodeUntilSupportUnlocksIt() {
        String[] ch = d.issue(p);
        String wrong = DeliveryFixture.wrongThan(ch[1]);
        for (int i = 0; i < 3; i++) {
            d.delivery.verify(UUID.fromString(ch[0]), wrong);
        }
        d.f.clock.advance(Duration.ofSeconds(61));

        expectCode(() -> d.delivery.requestChallenge(p.driver(), p.parcel()), "VERIFICATION_LOCKED");
    }
}
