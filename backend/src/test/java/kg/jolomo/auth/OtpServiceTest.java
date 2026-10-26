package kg.jolomo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kg.jolomo.shared.ApiException;
import kg.jolomo.sms.SmsOutboxService;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.Test;
import kg.jolomo.auth.OtpService.VerifyResult;

/** SP02-02, SP02-03: привязка к телефону и purpose, одноразовость, срок, бюджет попыток, лимиты. */
class OtpServiceTest extends PostgisTestBase {

    private static final String PHONE = "+996555000000";
    private static final String OTHER_PHONE = "+996555000111";

    private final MutableClock clock = new MutableClock();
    private final OtpSettings settings = new OtpSettings("test-pepper", Duration.ofMinutes(5), 5, Duration.ofSeconds(60), 5);

    private OtpService service() {
        SmsOutboxService sms = new SmsOutboxService(jdbc, tx, (c, p, b) -> true, clock);
        return new OtpService(jdbc, tx, sms, clock, settings);
    }

    /** Достаёт код из текста поставленного в outbox SMS (так делает и фейковый шлюз в dev). */
    private String lastCode(UUID challengeId) {
        String body = jdbc.sql("SELECT body FROM sms_messages WHERE command_id = :c")
                .param("c", "otp:" + challengeId)
                .query(String.class)
                .single();
        Matcher m = Pattern.compile("(\\d{6})").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void storesOnlyHashNeverTheRawCode() {
        OtpService otp = service();
        OtpService.Issued issued = otp.request(PHONE, Purpose.LOGIN);
        String code = lastCode(issued.challengeId());

        String stored = jdbc.sql("SELECT code_hash FROM auth_challenges WHERE id = :id")
                .param("id", issued.challengeId())
                .query(String.class)
                .single();

        assertThat(stored).isNotEqualTo(code).doesNotContain(code).hasSize(64);
        assertThat(issued.toString()).doesNotContain(code); // в ответе кода нет
    }

    @Test
    void codeIsOneTime() {
        OtpService otp = service();
        UUID id = otp.request(PHONE, Purpose.LOGIN).challengeId();
        String code = lastCode(id);

        assertThat(otp.verify(id, PHONE, Purpose.LOGIN, code)).isEqualTo(VerifyResult.OK);
        assertThat(otp.verify(id, PHONE, Purpose.LOGIN, code)).isEqualTo(VerifyResult.INVALID);
        assertThat(jdbc.sql("SELECT code_hash FROM auth_challenges WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .optional())
                .isEmpty(); // материал проверки стёрт после использования
    }

    @Test
    void codeIsBoundToPhone() {
        OtpService otp = service();
        UUID id = otp.request(PHONE, Purpose.LOGIN).challengeId();

        assertThat(otp.verify(id, OTHER_PHONE, Purpose.LOGIN, lastCode(id))).isEqualTo(VerifyResult.INVALID);
        assertThat(otp.verify(id, PHONE, Purpose.LOGIN, lastCode(id))).isEqualTo(VerifyResult.OK);
    }

    @Test
    void expiredCodeIsRejected() {
        OtpService otp = service();
        UUID id = otp.request(PHONE, Purpose.LOGIN).challengeId();
        String code = lastCode(id);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(otp.verify(id, PHONE, Purpose.LOGIN, code)).isEqualTo(VerifyResult.EXPIRED);
    }

    @Test
    void attemptBudgetIsExhaustedAndSurvivesRestart() {
        OtpService otp = service();
        UUID id = otp.request(PHONE, Purpose.LOGIN).challengeId();
        String right = lastCode(id);
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 4; i++) {
            assertThat(otp.verify(id, PHONE, Purpose.LOGIN, wrong)).isEqualTo(VerifyResult.INVALID);
        }
        assertThat(otp.verify(id, PHONE, Purpose.LOGIN, wrong)).isEqualTo(VerifyResult.EXHAUSTED);

        OtpService afterRestart = service();
        // Даже верный код после исчерпания бюджета отклоняется.
        assertThat(afterRestart.verify(id, PHONE, Purpose.LOGIN, right)).isEqualTo(VerifyResult.EXHAUSTED);
    }

    @Test
    void newRequestSupersedesPreviousCode() {
        OtpService otp = service();
        UUID first = otp.request(PHONE, Purpose.LOGIN).challengeId();
        String firstCode = lastCode(first);
        clock.advance(Duration.ofSeconds(61));
        UUID second = otp.request(PHONE, Purpose.LOGIN).challengeId();

        assertThat(otp.verify(first, PHONE, Purpose.LOGIN, firstCode)).isEqualTo(VerifyResult.INVALID);
        assertThat(otp.verify(second, PHONE, Purpose.LOGIN, lastCode(second))).isEqualTo(VerifyResult.OK);
    }

    @Test
    void resendTooSoonIsRejectedAndLimitSurvivesRestart() {
        service().request(PHONE, Purpose.LOGIN);

        assertThatThrownBy(() -> service().request(PHONE, Purpose.LOGIN))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("OTP_TOO_SOON"));

        clock.advance(Duration.ofSeconds(61));
        assertThat(service().request(PHONE, Purpose.LOGIN).challengeId()).isNotNull();
    }

    @Test
    void hourlyLimitIsEnforced() {
        for (int i = 0; i < 5; i++) {
            service().request(PHONE, Purpose.LOGIN);
            clock.advance(Duration.ofSeconds(61));
        }

        assertThatThrownBy(() -> service().request(PHONE, Purpose.LOGIN))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("OTP_RESEND_LIMIT"));
    }

    @Test
    void staleMaterialIsPurged() {
        OtpService otp = service();
        UUID id = otp.request(PHONE, Purpose.LOGIN).challengeId();

        clock.advance(Duration.ofHours(2));
        otp.purgeStaleMaterial();

        assertThat(jdbc.sql("SELECT code_hash FROM auth_challenges WHERE id = :id")
                        .param("id", id)
                        .query(String.class)
                        .optional())
                .isEmpty();
    }
}
