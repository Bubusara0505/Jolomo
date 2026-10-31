package kg.jolomo.push;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web Push: открытый ключ для браузера (публично) и opt-in/отписка человека (сессия, CSRF на изменения).
 * Если человек не разрешил уведомления, фронтенд просто не вызывает эти маршруты: ошибки в этом нет.
 */
@RestController
@RequestMapping("/api/v1")
class PushController {

    record PublicKeyView(String publicKey) {}

    /** Тело совпадает с PushSubscription.toJSON() браузера. Ключи {@code keys} принимаем и не храним: нагрузка не шифруется. */
    record SubscribeRequest(@NotBlank @Size(max = 2048) String endpoint, Long expirationTime, Object keys) {}

    record RevokeRequest(@NotBlank @Size(max = 2048) String endpoint) {}

    record SubscriptionView(UUID id, Instant createdAt, Instant expiresAt) {}

    private final SessionAuth auth;
    private final PushSubscriptionService subscriptions;
    private final PushSettings settings;

    PushController(SessionAuth auth, PushSubscriptionService subscriptions, PushSettings settings) {
        this.auth = auth;
        this.subscriptions = subscriptions;
        this.settings = settings;
    }

    @GetMapping("/push/vapid-key")
    PublicKeyView publicKey() {
        if (!settings.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_DISABLED", "error.push.disabled");
        }
        return new PublicKeyView(settings.publicKey().trim());
    }

    @GetMapping("/me/push-subscriptions")
    List<SubscriptionView> mine(HttpServletRequest request) {
        UUID userId = auth.requireUserId(request);
        return subscriptions.list(userId).stream().map(PushController::view).toList();
    }

    @PostMapping("/me/push-subscriptions")
    SubscriptionView subscribe(HttpServletRequest request, @Valid @RequestBody SubscribeRequest body) {
        UUID userId = auth.requireUserId(request);
        return view(subscriptions.subscribe(userId, body.endpoint(), body.expirationTime()));
    }

    @DeleteMapping("/me/push-subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(HttpServletRequest request, @PathVariable UUID id) {
        UUID userId = auth.requireUserId(request);
        subscriptions.revoke(userId, id);
    }

    @PostMapping("/me/push-subscriptions/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeByEndpoint(HttpServletRequest request, @Valid @RequestBody RevokeRequest body) {
        UUID userId = auth.requireUserId(request);
        subscriptions.revokeByEndpoint(userId, body.endpoint());
    }

    private static SubscriptionView view(PushSubscriptionService.Subscription s) {
        return new SubscriptionView(s.id(), s.createdAt(), s.expiresAt());
    }
}
