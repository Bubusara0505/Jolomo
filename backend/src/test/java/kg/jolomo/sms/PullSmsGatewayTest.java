package kg.jolomo.sms;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** SP10-01: push-отправщик не должен работать вместе с шлюзом, который сам забирает сообщения. */
class PullSmsGatewayTest {

    @Test
    void androidGatewayWithTheDispatcherStillOnRefusesToStart() {
        assertThatThrownBy(() -> new PullSmsGateway(true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JOLOMO_SMS_DISPATCHER_ENABLED=false");
    }

    @Test
    void withTheDispatcherOffItStartsButNeverSendsByItself() {
        PullSmsGateway gw = new PullSmsGateway(false);

        assertThatCode(() -> new PullSmsGateway(false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> gw.submit("c", "+996555000001", "x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
