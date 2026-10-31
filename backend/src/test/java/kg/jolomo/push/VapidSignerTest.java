package kg.jolomo.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import kg.jolomo.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

/** SP11-02: подпись VAPID проверяется настоящим открытым ключом, формат совпадает с RFC 8292. */
class VapidSignerTest {

    private final VapidTestKeys keys = VapidTestKeys.generate();
    private final MutableClock clock = new MutableClock();

    private VapidSigner signer() {
        return new VapidSigner(keys.publicKey(), keys.privateKey(), "mailto:ops@jolomo.kg", clock);
    }

    @Test
    void headerHasTheVapidShapeAndTheSignatureVerifies() throws Exception {
        String header = signer().authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc"));

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + keys.publicKey());
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(keys.ecPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void claimsNameTheServiceOriginExpiryAndContact() {
        String header = signer().authorization(URI.create("https://Updates.Push.Services.Mozilla.com/wpush/v2/x?y=1"));
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");

        String head = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        long exp = Instant.parse("2026-10-26T09:00:00Z").plusSeconds(12 * 3600).getEpochSecond();

        assertThat(head).isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        assertThat(claims).isEqualTo("{\"aud\":\"https://updates.push.services.mozilla.com\",\"exp\":" + exp + ",\"sub\":\"mailto:ops@jolomo.kg\"}");
    }

    @Test
    void theSignatureIsRawRAndSSixtyFourBytes() {
        String header = signer().authorization(URI.create("https://fcm.googleapis.com/x"));
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));

        assertThat(Base64.getUrlDecoder().decode(jwt.split("\\.")[2])).hasSize(64);
    }

    @Test
    void badKeysOrSubjectStopStartup() {
        assertThatThrownBy(() -> new VapidSigner("", keys.privateKey(), "mailto:a@b.kg", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VapidSigner(keys.publicKey(), "", "mailto:a@b.kg", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VapidSigner("AAAA", keys.privateKey(), "mailto:a@b.kg", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VapidSigner(keys.publicKey(), "AAAA", "mailto:a@b.kg", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VapidSigner(keys.publicKey(), keys.privateKey(), "ops@jolomo.kg", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VapidSigner(keys.publicKey(), keys.privateKey(), "mailto:a\"b@jolomo.kg", clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new VapidSigner("***", keys.privateKey(), "mailto:a@b.kg", clock)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void errorMessagesNeverContainTheKeys() {
        assertThatThrownBy(() -> new VapidSigner(keys.publicKey(), "AAAA", "mailto:a@b.kg", clock))
                .hasMessageNotContaining("AAAA").hasMessageNotContaining(keys.privateKey());
    }
}
