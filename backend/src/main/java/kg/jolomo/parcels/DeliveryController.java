package kg.jolomo.parcels;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Код доставки. Водитель просит отправить код получателю (вход и CSRF как у всех действий водителя); получатель
 * без аккаунта проверяет код по ссылке из SMS. Ответы получателю не раскрывают, существует ли посылка: неверная,
 * чужая или заменённая ссылка даёт один и тот же not-found. Кода и ссылки в ответах водителю нет.
 */
@RestController
@RequestMapping("/api/v1")
class DeliveryController {

    record VerifyRequest(@NotBlank @Size(max = 16) String code) {}

    record IssuedView(java.time.Instant expiresAt, int resendAfterSeconds) {}

    record DeliveredView(String status) {}

    private final SessionAuth auth;
    private final DeliveryService delivery;

    DeliveryController(SessionAuth auth, DeliveryService delivery) {
        this.auth = auth;
        this.delivery = delivery;
    }

    @PostMapping("/parcels/{id}/delivery-challenge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    IssuedView request(HttpServletRequest request, @PathVariable UUID id) {
        DeliveryService.Issued issued = delivery.requestChallenge(auth.requireUserId(request), id);
        return new IssuedView(issued.expiresAt(), issued.resendAfterSeconds());
    }

    /** Состояние проверки для страницы получателя. Без входа: доступом служит сама ссылка. */
    @GetMapping("/deliveries/{challengeId}")
    ResponseEntity<DeliveryService.ChallengeView> state(@PathVariable UUID challengeId) {
        DeliveryService.ChallengeView view = delivery.view(challengeId).orElseThrow(DeliveryController::notFound);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(view);
    }

    @PostMapping("/deliveries/{challengeId}/verify")
    ResponseEntity<DeliveredView> verify(@PathVariable UUID challengeId, @Valid @RequestBody VerifyRequest body) {
        DeliveryService.VerifyOutcome outcome = delivery.verify(challengeId, body.code());
        return switch (outcome) {
            case DELIVERED -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new DeliveredView("DELIVERED"));
            case INVALID_CODE -> throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_CODE_INVALID", "error.delivery.code_invalid");
            case LOCKED -> throw new ApiException(HttpStatus.LOCKED, "DELIVERY_LOCKED", "error.delivery.locked");
            case EXPIRED -> throw new ApiException(HttpStatus.GONE, "DELIVERY_EXPIRED", "error.delivery.expired");
            case ALREADY_USED -> throw new ApiException(HttpStatus.CONFLICT, "DELIVERY_ALREADY_VERIFIED", "error.delivery.already_verified");
            case NOT_FOUND -> throw notFound();
        };
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND", "error.delivery.not_found");
    }
}
