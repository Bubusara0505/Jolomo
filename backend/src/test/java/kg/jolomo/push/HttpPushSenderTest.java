package kg.jolomo.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import kg.jolomo.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP11-02: что именно уходит push-службе и как читаются её ответы. Настоящая служба заменена локальным сервером. */
class HttpPushSenderTest {

    private HttpServer server;
    private final CopyOnWriteArrayList<String> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger bodyBytes = new AtomicInteger(-1);
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private volatile int status = 201;
    private HttpPushSender sender;
    private final VapidTestKeys keys = VapidTestKeys.generate();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", ex -> {
            seen.add(ex.getRequestMethod());
            seen.add("Authorization=" + ex.getRequestHeaders().getFirst("Authorization"));
            seen.add("TTL=" + ex.getRequestHeaders().getFirst("TTL"));
            seen.add("Urgency=" + ex.getRequestHeaders().getFirst("Urgency"));
            bodyBytes.set(ex.getRequestBody().readAllBytes().length);
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/target", ex -> {
            redirectTargetHits.incrementAndGet();
            ex.sendResponseHeaders(201, -1);
            ex.close();
        });
        server.start();
        VapidSigner signer = new VapidSigner(keys.publicKey(), keys.privateKey(), "mailto:ops@jolomo.kg", new MutableClock());
        sender = new HttpPushSender(HttpPushSender.newClient(), signer);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void theRequestIsAnEmptyPostWithVapidTtlAndUrgency() {
        PushSender.Outcome outcome = sender.send(url("/push"));

        assertThat(outcome).isEqualTo(PushSender.Outcome.ACCEPTED);
        assertThat(seen.get(0)).isEqualTo("POST");
        assertThat(seen.get(1)).startsWith("Authorization=vapid t=").endsWith(", k=" + keys.publicKey());
        assertThat(seen.get(2)).isEqualTo("TTL=3600");
        assertThat(seen.get(3)).isEqualTo("Urgency=normal");
        // Нагрузки нет вообще: в push не может быть контактов или кода.
        assertThat(bodyBytes.get()).isZero();
    }

    @Test
    void responseCodesMapToAcceptedGoneOrFailed() {
        for (int ok : new int[] {200, 201, 202}) {
            status = ok;
            assertThat(sender.send(url("/push"))).isEqualTo(PushSender.Outcome.ACCEPTED);
        }
        for (int gone : new int[] {404, 410}) {
            status = gone;
            assertThat(sender.send(url("/push"))).isEqualTo(PushSender.Outcome.GONE);
        }
        for (int bad : new int[] {400, 401, 403, 413, 429, 500, 503}) {
            status = bad;
            assertThat(sender.send(url("/push"))).isEqualTo(PushSender.Outcome.FAILED);
        }
    }

    @Test
    void redirectsAreNeverFollowed() {
        PushSender.Outcome outcome = sender.send(url("/redirect"));

        assertThat(outcome).isEqualTo(PushSender.Outcome.FAILED);
        assertThat(redirectTargetHits.get()).isZero();
    }

    @Test
    void anUnreachableServiceIsAFailureNotAnException() {
        server.stop(0);

        assertThat(sender.send(url("/push"))).isEqualTo(PushSender.Outcome.FAILED);
    }
}
