package kg.jolomo.admin;

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
 * Cookie администратора: HttpOnly; Secure; SameSite=Strict, только для /api/v1/admin. Пользовательские cookie
 * сюда не подходят и наоборот. CSRF-токен общий с пользовательской частью (jl_csrf, double submit).
 */
@Component
class AdminCookies {

    static final String SESSION = "jl_admin";
    static final String CSRF = "jl_csrf";
    static final String PATH = "/api/v1/admin";

    private final AdminSettings settings;
    private final SecureRandom random = new SecureRandom();

    AdminCookies(AdminSettings settings) {
        this.settings = settings;
    }

    List<String> issue(String token, Duration ttl) {
        byte[] b = new byte[32];
        random.nextBytes(b);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        return List.of(
                build(SESSION, token, PATH, ttl, true, "Strict"),
                build(CSRF, csrf, "/", ttl, false, "Lax"));
    }

    List<String> clear() {
        return List.of(
                build(SESSION, "", PATH, Duration.ZERO, true, "Strict"),
                build(CSRF, "", "/", Duration.ZERO, false, "Lax"));
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

    private String build(String name, String value, String path, Duration maxAge, boolean httpOnly, String sameSite) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(settings.cookieSecure())
                .sameSite(sameSite)
                .path(path)
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
