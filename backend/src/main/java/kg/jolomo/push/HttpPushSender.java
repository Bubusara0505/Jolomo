package kg.jolomo.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Отправка по протоколу Web Push без шифрованной нагрузки: POST с пустым телом, заголовки VAPID, TTL и Urgency.
 * Переходы по редиректам выключены: ответ push-службы не должен уводить сервер на другой адрес.
 * Адрес должен быть заранее проверен {@link EndpointPolicy}; этот класс политику не повторяет.
 */
public final class HttpPushSender implements PushSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Сколько push-служба держит сообщение, если устройство выключено. Разбудить устарело быстро, час достаточно. */
    private static final String TTL_SECONDS = "3600";

    private final HttpClient http;
    private final VapidSigner signer;

    public HttpPushSender(HttpClient http, VapidSigner signer) {
        this.http = http;
        this.signer = signer;
    }

    public static HttpClient newClient() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public Outcome send(URI endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("Authorization", signer.authorization(endpoint))
                    .header("TTL", TTL_SECONDS)
                    .header("Urgency", "normal")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return Outcome.ACCEPTED;
            }
            return status == 404 || status == 410 ? Outcome.GONE : Outcome.FAILED;
        } catch (IOException | RuntimeException e) {
            return Outcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        }
    }
}
