package kg.jolomo.bookings;

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
 * Брони (SP06). Все маршруты требуют входа; права (допуск водителя, владение поездкой, участие в брони) решает сервер.
 * Запрос места требует заголовок Idempotency-Key. Принятие, отказ и отмена безопасны при повторе сами по себе.
 * Телефонов в ответах нет (контакты откроет SP07-01 после ACCEPTED).
 */
@RestController
@RequestMapping("/api/v1")
class BookingController {

    record SeatsRequest(Integer seats) {}

    private final SessionAuth auth;
    private final BookingService bookings;

    BookingController(SessionAuth auth, BookingService bookings) {
        this.auth = auth;
        this.bookings = bookings;
    }

    @PostMapping("/trips/{tripId}/bookings")
    ResponseEntity<String> request(
            HttpServletRequest request,
            @PathVariable UUID tripId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) SeatsRequest body) {
        UUID user = auth.requireUserId(request);
        IdempotencyService.requireValidKey(key);
        if (body == null || body.seats() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEATS", "error.booking.invalid_seats");
        }
        IdempotencyService.Result result = bookings.requestWithKey(user, tripId, body.seats(), key);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    /** Запросы на свою поездку (для водителя). */
    @GetMapping("/trips/{tripId}/bookings")
    List<BookingService.Booking> forTrip(HttpServletRequest request, @PathVariable UUID tripId) {
        return bookings.listForTrip(auth.requireUserId(request), tripId);
    }

    @GetMapping("/bookings/mine")
    List<BookingService.Booking> mine(HttpServletRequest request) {
        return bookings.listMine(auth.requireUserId(request));
    }

    @GetMapping("/bookings/{id}")
    BookingService.Booking get(HttpServletRequest request, @PathVariable UUID id) {
        UUID user = auth.requireUserId(request);
        return bookings.findVisible(user, id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "error.booking.not_found"));
    }

    @PostMapping("/bookings/{id}/accept")
    BookingService.Booking accept(HttpServletRequest request, @PathVariable UUID id) {
        return bookings.accept(auth.requireUserId(request), id);
    }

    @PostMapping("/bookings/{id}/reject")
    BookingService.Booking reject(HttpServletRequest request, @PathVariable UUID id) {
        return bookings.reject(auth.requireUserId(request), id);
    }

    @PostMapping("/bookings/{id}/cancel")
    BookingService.Booking cancel(HttpServletRequest request, @PathVariable UUID id) {
        return bookings.cancel(auth.requireUserId(request), id);
    }

    /** Водитель фиксирует, что пассажир поехал. Только после отправления. */
    @PostMapping("/bookings/{id}/complete")
    BookingService.Booking complete(HttpServletRequest request, @PathVariable UUID id) {
        return bookings.complete(auth.requireUserId(request), id);
    }

    /** Водитель фиксирует неявку пассажира. Только после отправления. */
    @PostMapping("/bookings/{id}/no-show")
    BookingService.Booking noShow(HttpServletRequest request, @PathVariable UUID id) {
        return bookings.markNoShow(auth.requireUserId(request), id);
    }
}
