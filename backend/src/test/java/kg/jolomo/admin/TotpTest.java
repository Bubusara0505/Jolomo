package kg.jolomo.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** TOTP по RFC 6238 (контрольные значения из приложения B стандарта, последние 6 цифр) и Base32 по RFC 4648. */
class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238Vectors() {
        assertThat(Totp.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 1111111111L / 30)).isEqualTo("050471");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void base32RoundTripAndKnownValue() {
        assertThat(Base32.encode(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Base32.decode("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(RFC_SECRET);
    }

    @Test
    void windowIsPlusMinusOneStep() {
        long now = 1_800_000_000L;
        long step = Totp.stepAt(now);

        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, 0)).isEqualTo(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 0)).isEqualTo(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, 0)).isEqualTo(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, 0)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, 0)).isEqualTo(-1);
    }

    @Test
    void usedStepCannotBeReplayed() {
        long now = 1_800_000_000L;
        long step = Totp.stepAt(now);
        String code = Totp.code(RFC_SECRET, step);

        assertThat(Totp.verify(RFC_SECRET, code, now, step)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, code, now, step - 1)).isEqualTo(step);
    }

    @Test
    void malformedInputIsRejected() {
        assertThat(Totp.verify(RFC_SECRET, null, 1_800_000_000L, 0)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "12345", 1_800_000_000L, 0)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "abcdef", 1_800_000_000L, 0)).isEqualTo(-1);
    }
}
