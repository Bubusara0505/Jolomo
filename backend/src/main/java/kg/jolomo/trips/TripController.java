package kg.jolomo.trips;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Поездки (SP05-01): создаёт, правит, публикует и отменяет только владелец. Права решает сервер:
 * допуск водителя и выключатель обязательств проверяются заново при публикации и правке опубликованной поездки.
 * Поиск по направлению и окну (GET /api/v1/trips) появится в SP05-02.
 */
@RestController
@RequestMapping("/api/v1/trips")
class TripController {

    record CreateRequest(
            Direction direction,
            UUID pickupLocationId,
            Instant departureFrom,
            Instant departureTo,
            Integer seatsTotal,
            Integer priceKgs,
            Integer parcelSlotsTotal) {}

    /** Правка полей или смена статуса (PUBLISHED или CANCELLED), но не то и другое сразу. */
    record PatchRequest(
            Direction direction,
            UUID pickupLocationId,
            Instant departureFrom,
            Instant departureTo,
            Integer seatsTotal,
            Integer priceKgs,
            Integer parcelSlotsTotal,
            String status) {}

    record OwnerView(UUID id, TripStatus status, Direction direction, UUID pickupLocationId, Instant departureFrom,
                     Instant departureTo, int seatsTotal, int seatsAvailable, int priceKgs, String currency,
                     int parcelSlotsTotal, int parcelSlotsAvailable) {}

    private final SessionAuth auth;
    private final TripService trips;

    TripController(SessionAuth auth, TripService trips) {
        this.auth = auth;
        this.trips = trips;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OwnerView create(HttpServletRequest request, @RequestBody CreateRequest body) {
        UUID user = auth.requireUserId(request);
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "error.trip.invalid_request");
        }
        return view(trips.createDraft(user, new TripService.TripDraft(
                body.direction(), body.pickupLocationId(), body.departureFrom(), body.departureTo(),
                orZero(body.seatsTotal()), orZero(body.priceKgs()), orZero(body.parcelSlotsTotal()))));
    }

    @GetMapping("/mine")
    List<OwnerView> mine(HttpServletRequest request) {
        return trips.listOwn(auth.requireUserId(request)).stream().map(TripController::view).toList();
    }

    /** Владельцу своя поездка в любом статусе; остальным только опубликованная, в публичном виде. */
    @GetMapping("/{id}")
    Object get(HttpServletRequest request, @PathVariable UUID id) {
        Optional<UUID> user = auth.userIdIfPresent(request);
        Optional<TripService.Trip> own = trips.find(id).filter(t -> user.isPresent() && t.ownerId().equals(user.get()));
        if (own.isPresent()) {
            return view(own.get());
        }
        return trips.findPublic(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "error.trip.notFound"));
    }

    @PatchMapping("/{id}")
    OwnerView patch(HttpServletRequest request, @PathVariable UUID id, @RequestBody PatchRequest body) {
        UUID user = auth.requireUserId(request);
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "error.trip.invalid_request");
        }
        TripService.TripPatch fields = new TripService.TripPatch(
                body.direction(), body.pickupLocationId(), body.departureFrom(), body.departureTo(),
                body.seatsTotal(), body.priceKgs(), body.parcelSlotsTotal());
        if (body.status() == null) {
            return view(trips.update(user, id, fields));
        }
        if (!isEmpty(fields)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "error.trip.invalid_request");
        }
        return switch (body.status()) {
            case "PUBLISHED" -> view(trips.publish(user, id));
            case "CANCELLED" -> view(trips.cancel(user, id));
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "error.trip.invalid_status");
        };
    }

    /** Отсутствующее число даёт 0, и сервис отвечает понятным кодом (INVALID_SEATS и т. п.), а не ошибкой разбора. */
    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean isEmpty(TripService.TripPatch p) {
        return p.direction() == null && p.pickupLocationId() == null && p.departureFrom() == null && p.departureTo() == null
                && p.seatsTotal() == null && p.priceKgs() == null && p.parcelSlotsTotal() == null;
    }

    private static OwnerView view(TripService.Trip t) {
        return new OwnerView(t.id(), t.status(), t.direction(), t.pickupLocationId(), t.departureFrom(), t.departureTo(),
                t.seatsTotal(), t.seatsAvailable(), t.priceKgs(), "KGS", t.parcelSlotsTotal(), t.parcelSlotsAvailable());
    }
}
