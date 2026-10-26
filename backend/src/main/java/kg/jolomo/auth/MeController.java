package kg.jolomo.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Только сам субъект: телефон возвращается владельцу сессии и больше никому. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    record MeResponse(UUID id, String phone) {}

    private final SessionService sessions;

    MeController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    MeResponse me(HttpServletRequest request) {
        SessionService.Principal p = SessionCookies.read(request, SessionCookies.ACCESS)
                .flatMap(sessions::authenticate)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));
        SessionService.MeView me = sessions.me(p.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));
        return new MeResponse(me.id(), me.phone());
    }
}
