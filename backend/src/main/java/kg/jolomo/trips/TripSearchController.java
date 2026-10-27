package kg.jolomo.trips;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Публичный поиск: вход не нужен, в ответе нет владельца, телефонов и документов. */
@RestController
@RequestMapping("/api/v1/trips")
class TripSearchController {

    record SearchResponse(List<TripSearchService.Hit> items, int limit, int offset) {}

    private final TripSearchService search;

    TripSearchController(TripSearchService search) {
        this.search = search;
    }

    @GetMapping
    SearchResponse search(
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID nearPickupLocationId,
            @RequestParam(required = false) Integer radiusMeters,
            @RequestParam(required = false) Integer seats,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        List<TripSearchService.Hit> items = search.search(new TripSearchService.Query(
                direction, from, to, nearPickupLocationId, radiusMeters, seats, limit, offset));
        return new SearchResponse(items, limit != null ? limit : TripSearchService.DEFAULT_LIMIT, offset != null ? offset : 0);
    }
}
