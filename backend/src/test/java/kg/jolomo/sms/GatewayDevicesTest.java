package kg.jolomo.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** SP10-01: токены устройств шлюза и защита от неверной настройки. */
class GatewayDevicesTest {

    private static final String T1 = "token-one-0123456789";
    private static final String T2 = "token-two-0123456789";

    private GatewayDevices two() {
        return new GatewayDevices("pixel-1:" + GatewayDevices.hashOf(T1) + ", pixel-2:" + GatewayDevices.hashOf(T2));
    }

    @Test
    void aValidTokenGivesTheDeviceName() {
        assertThat(two().authenticate(T1)).contains("pixel-1");
        assertThat(two().authenticate(T2)).contains("pixel-2");
    }

    @Test
    void anythingElseIsRefused() {
        GatewayDevices d = two();
        assertThat(d.authenticate(null)).isEmpty();
        assertThat(d.authenticate("")).isEmpty();
        assertThat(d.authenticate("  ")).isEmpty();
        assertThat(d.authenticate("token-one-0123456780")).isEmpty();
        assertThat(d.authenticate(GatewayDevices.hashOf(T1))).isEmpty();   // хеш токеном не является
    }

    @Test
    void anEmptySpecMeansNoDevicesAndNobodyGetsIn() {
        assertThat(new GatewayDevices("").isEmpty()).isTrue();
        assertThat(new GatewayDevices(null).authenticate(T1)).isEmpty();
    }

    @Test
    void aBrokenSpecStopsTheStartup() {
        String h = GatewayDevices.hashOf(T1);
        assertThatThrownBy(() -> new GatewayDevices("pixel-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new GatewayDevices("Pixel_1:" + h)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new GatewayDevices("pixel-1:abc")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new GatewayDevices("pixel-1:" + h + ",pixel-1:" + h)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void settingsMustBePositive() {
        assertThatThrownBy(() -> new GatewaySettings(java.time.Duration.ZERO, java.time.Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new GatewaySettings(java.time.Duration.ofSeconds(1), java.time.Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
