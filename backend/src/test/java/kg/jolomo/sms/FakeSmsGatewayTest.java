package kg.jolomo.sms;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Код SMS в журнале допустим только в профиле dev. */
class FakeSmsGatewayTest {

    @Test
    void logBodyWithoutDevProfileRefusesToStart() {
        assertThatThrownBy(() -> new FakeSmsGateway(true, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void logBodyInDevProfileIsAllowed() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> new FakeSmsGateway(true, dev)).doesNotThrowAnyException();
    }

    @Test
    void defaultNeverLogsBody() {
        assertThatCode(() -> new FakeSmsGateway(false, new MockEnvironment())).doesNotThrowAnyException();
    }
}
