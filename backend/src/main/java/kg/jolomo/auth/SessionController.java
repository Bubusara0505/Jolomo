package kg.jolomo.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import kg.jolomo.shared.ApiError;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class SessionController {

    record CsrfView(String csrfToken) {}

    private final SessionService sessions;
    private final SessionCookies cookies;

    SessionController(SessionService sessions, SessionCookies cookies) {
        this.sessions = sessions;
        this.cookies = cookies;
    }

    /** Обмен refresh на новую пару. Refresh берётся из cookie. */
    @PostMapping("/refresh")
    ResponseEntity<?> refresh(HttpServletRequest request) {
        String token = SessionCookies.read(request, SessionCookies.REFRESH).orElse(null);
        SessionService.RefreshResult result = sessions.refresh(token);
        return switch (result.outcome()) {
            case OK -> noContent(cookies.issue(result.tokens()));
            case INVALID -> unauthorized("SESSION_INVALID", "error.session.invalid");
            case REUSED -> unauthorized("SESSION_REUSED", "error.session.reused");
        };
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(SessionCookies.read(request, SessionCookies.REFRESH).orElse(null));
        return noContent(cookies.clear());
    }

    /** Выдаёт CSRF-токен, если cookie потерялась (например, после очистки браузером). */
    @GetMapping("/csrf")
    ResponseEntity<CsrfView> csrf(HttpServletRequest request) {
        Optional<String> existing = SessionCookies.read(request, SessionCookies.CSRF);
        String token = existing.orElseGet(cookies::newCsrfToken);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (existing.isEmpty()) {
            response.header(HttpHeaders.SET_COOKIE, cookies.csrfCookie(token));
        }
        return response.body(new CsrfView(token));
    }

    private static <T> ResponseEntity<T> noContent(List<String> setCookies) {
        return ResponseEntity.noContent().headers(h -> h.addAll(HttpHeaders.SET_COOKIE, setCookies)).build();
    }

    private ResponseEntity<ApiError> unauthorized(String code, String key) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .headers(h -> h.addAll(HttpHeaders.SET_COOKIE, cookies.clear()))
                .body(ApiError.of(code, key));
    }
}
