package kg.jolomo.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Единая точка «кто вызывает»: пользователь из access-cookie или 401. Права проверяет сервер, не клиент. */
@Component
public class SessionAuth {

    private final SessionService sessions;

    SessionAuth(SessionService sessions) {
        this.sessions = sessions;
    }

    public UUID requireUserId(HttpServletRequest request) {
        return SessionCookies.read(request, SessionCookies.ACCESS)
                .flatMap(sessions::authenticate)
                .map(SessionService.Principal::userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));
    }
}
