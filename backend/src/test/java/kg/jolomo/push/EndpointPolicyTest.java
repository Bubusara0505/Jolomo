package kg.jolomo.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SP11-02: адрес подписки приходит от клиента, поэтому вызываем только известные push-службы по https. */
class EndpointPolicyTest {

    private final EndpointPolicy policy = new EndpointPolicy(PushSettings.DEFAULT_ALLOWED_HOSTS);

    @Test
    void knownPushServicesAreAccepted() {
        for (String endpoint : List.of(
            "https://fcm.googleapis.com/fcm/send/abc:def",
            "https://updates.push.services.mozilla.com/wpush/v2/gAAAA",
            "https://web.push.apple.com/QGxx",
            "https://wns2-par02p.notify.windows.com/w/?token=abc",
            "https://FCM.GoogleAPIs.com/fcm/send/x",
            "https://fcm.googleapis.com:443/fcm/send/x"
        )) {
            assertThat(policy.check(endpoint)).as(endpoint).isPresent();
        }
    }

    @Test
    void everythingElseIsRejected() {
        for (String endpoint : List.of(
            "http://fcm.googleapis.com/fcm/send/x",
            "https://127.0.0.1/x",
            "https://localhost/x",
            "https://169.254.169.254/latest/meta-data",
            "https://evil.example/fcm.googleapis.com",
            "https://fcm.googleapis.com.evil.example/x",
            "https://evilfcm.googleapis.com/x",
            "https://user:pass@fcm.googleapis.com/x",
            "https://fcm.googleapis.com@evil.example/x",
            "https://fcm.googleapis.com:8443/x",
            "https://fcm.googleapis.com/x#frag",
            "ftp://fcm.googleapis.com/x",
            "file:///etc/passwd",
            "not a url",
            "",
            "//fcm.googleapis.com/x"
        )) {
            assertThat(policy.check(endpoint)).as(endpoint).isEmpty();
        }
    }

    @Test
    void nullAndOversizedAreRejected() {
        assertThat(policy.check(null)).isEmpty();
        assertThat(policy.check("https://fcm.googleapis.com/" + "a".repeat(2100))).isEmpty();
    }

    @Test
    void theAllowListIsConfigurable() {
        EndpointPolicy custom = new EndpointPolicy(List.of("push.example.kg"));

        assertThat(custom.check("https://a.push.example.kg/x")).isPresent();
        assertThat(custom.check("https://fcm.googleapis.com/x")).isEmpty();
    }
}
