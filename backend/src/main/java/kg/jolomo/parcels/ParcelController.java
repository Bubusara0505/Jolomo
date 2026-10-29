package kg.jolomo.parcels;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.IdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Посылки (SP07-04). Создание требует входа, CSRF и Idempotency-Key. Маршрута, которым клиент включает набор
 * обязательств, нет и быть не должно. В ответах нет телефонов.
 */
@RestController
@RequestMapping("/api/v1")
class ParcelController {

    record CreateRequest(UUID tripId, String size, String category, String recipientPhone) {}

    private final SessionAuth auth;
    private final ParcelService parcels;

    ParcelController(SessionAuth auth, ParcelService parcels) {
        this.auth = auth;
        this.parcels = parcels;
    }

    @PostMapping("/parcels")
    ResponseEntity<String> create(
            HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) CreateRequest body) {
        UUID user = auth.requireUserId(request);
        IdempotencyService.requireValidKey(key);
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRIP", "error.parcel.invalid_trip");
        }
        IdempotencyService.Result result = parcels.createWithKey(user,
                new ParcelService.NewParcel(body.tripId(), body.size(), body.category(), body.recipientPhone()), key);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/parcels/mine")
    List<ParcelService.Parcel> mine(HttpServletRequest request) {
        return parcels.listMine(auth.requireUserId(request));
    }

    @GetMapping("/parcels/{id}")
    ParcelService.Parcel get(HttpServletRequest request, @PathVariable UUID id) {
        UUID user = auth.requireUserId(request);
        return parcels.findVisible(user, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PARCEL_NOT_FOUND", "error.parcel.not_found"));
    }

    @GetMapping("/trips/{tripId}/parcels")
    List<ParcelService.Parcel> forTrip(HttpServletRequest request, @PathVariable UUID tripId) {
        return parcels.listForTrip(auth.requireUserId(request), tripId);
    }
}
