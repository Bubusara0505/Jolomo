package kg.jolomo.push;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Какие адреса подписки сервер вообще готов вызывать. Адрес приходит от клиента, и без этой проверки сервер можно было бы
 * заставить обращаться к чему угодно (SSRF). Нужны: https, стандартный порт, без логина в адресе и домен из списка
 * известных push-служб браузеров (точное совпадение или поддомен).
 */
public final class EndpointPolicy {

    private final List<String> allowedHosts;

    public EndpointPolicy(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
    }

    /** Пусто, если адрес не подходит. */
    public Optional<URI> check(String endpoint) {
        if (endpoint == null || endpoint.isEmpty() || endpoint.length() > 2048) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null) {
            return Optional.empty();
        }
        String h = host.toLowerCase(Locale.ROOT);
        boolean known = allowedHosts.stream().anyMatch(a -> h.equals(a) || h.endsWith("." + a));
        return known ? Optional.of(uri) : Optional.empty();
    }
}
