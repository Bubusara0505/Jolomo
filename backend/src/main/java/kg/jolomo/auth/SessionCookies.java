package kg.jolomo.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Cookie сессии: access и refresh HttpOnly; Secure; SameSite=Lax. Токенов в localStorage нет.
 * CSRF-токен (double submit) читается JS и отправляется в заголовке X-CSRF-Token.
 */
@Component
class SessionCookies {

    static final String ACCESS = "jl_access";
    static final String REFRESH = "jl_refresh";
    static final String CSRF = "jl_csrf";
    static final String CSRF_HEADER = "X-CSRF-Token";
    /** Refresh уходит только на эндпоинты auth. */
    static final String REFRESH_PATH = "/api/v1/auth";

    private final SessionSettings settings;
    private final SecureRandom random = new SecureRandom();

    SessionCookies(SessionSettings settings) {
        this.settings = settings;
    }

    /** Значения Set-Cookie для новой пары токенов и свежего CSRF-токена. */
    List<String> issue(SessionService.Tokens t) {
        return List.of(
                cookie(ACCESS, t.access(), "/", t.accessTtl(), true),
                cookie(REFRESH, t.refresh(), REFRESH_PATH, t.refreshTtl(), true),
                csrfCookie(newCsrfToken(), t.refreshTtl()));
    }

    List<String> clear() {
        return List.of(
                cookie(ACCESS, "", "/", Duration.ZERO, true),
                cookie(REFRESH, "", REFRESH_PATH, Duration.ZERO, true),
                cookie(CSRF, "", "/", Duration.ZERO, false));
    }

    String csrfCookie(String value) {
        return csrfCookie(value, settings.refreshTtl());
    }

    String csrfCookie(String value, Duration ttl) {
        return cookie(CSRF, value, "/", ttl, false);
    }

    String newCsrfToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName()) && c.getValue() != null && !c.getValue().isEmpty()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    private String cookie(String name, String value, String path, Duration maxAge, boolean httpOnly) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(settings.cookieSecure())
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
