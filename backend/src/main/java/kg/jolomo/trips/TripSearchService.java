package kg.jolomo.trips;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Публичный поиск поездок (SP05-02). Возвращает только опубликованные, ещё не завершившие отправление поездки нужного
 * направления, где осталось достаточно мест. Окно отправления задаётся пересечением, расстояние считается по geography
 * (метры) от точки каталога. Телефонов, владельца и документов в ответе нет.
 */
@Service
public class TripSearchService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_OFFSET = 10_000;
    public static final int DEFAULT_RADIUS_METERS = 5_000;
    public static final int MAX_RADIUS_METERS = 100_000;

    public record Query(
            Direction direction,
            Instant from,
            Instant to,
            UUID nearPickupLocationId,
            Integer radiusMeters,
            Integer seats,
            Integer limit,
            Integer offset) {}

    public record Hit(UUID id, Direction direction, Instant departureFrom, Instant departureTo, String pickupCode,
                      String pickupNameKy, String pickupNameRu, String pickupNameEn, double pickupLatitude,
                      double pickupLongitude, int seatsAvailable, int priceKgs, String currency, Double distanceMeters) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public TripSearchService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<Hit> search(Query q) {
        if (q == null || q.direction() == null) {
            throw bad("INVALID_DIRECTION");
        }
        Instant now = clock.instant();
        Instant from = q.from() != null ? q.from() : now;
        if (q.to() != null && !q.to().isAfter(from)) {
            throw bad("INVALID_WINDOW");
        }
        int seats = q.seats() != null ? q.seats() : 1;
        if (seats < 1) {
            throw bad("INVALID_SEATS");
        }
        int limit = q.limit() != null ? q.limit() : DEFAULT_LIMIT;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw bad("INVALID_LIMIT");
        }
        int offset = q.offset() != null ? q.offset() : 0;
        if (offset < 0 || offset > MAX_OFFSET) {
            throw bad("INVALID_OFFSET");
        }
        int radius = q.radiusMeters() != null ? q.radiusMeters() : DEFAULT_RADIUS_METERS;
        if (radius < 1 || radius > MAX_RADIUS_METERS) {
            throw bad("INVALID_RADIUS");
        }
        boolean near = q.nearPickupLocationId() != null;
        if (near && !jdbc.sql("SELECT EXISTS (SELECT 1 FROM pickup_locations WHERE id = :id AND active)")
                .param("id", q.nearPickupLocationId()).query(Boolean.class).single()) {
            throw bad("PICKUP_NOT_IN_CATALOG");
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql(near, q.to() != null))
                .param("dir", q.direction().name())
                .param("now", utc(now))
                .param("from", utc(from))
                .param("seats", seats)
                .param("limit", limit)
                .param("offset", offset);
        if (q.to() != null) {
            spec = spec.param("to", utc(q.to()));
        }
        if (near) {
            spec = spec.param("ref", q.nearPickupLocationId()).param("radius", (double) radius);
        }
        return spec.query((rs, i) -> {
            return new Hit(
                    rs.getObject(1, UUID.class), Direction.valueOf(rs.getString(2)),
                    rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getObject(4, OffsetDateTime.class).toInstant(),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getDouble(9), rs.getDouble(10),
                    rs.getInt(11), rs.getInt(12), rs.getString(13), rs.getObject(14, Double.class));
        }).list();
    }

    /** Текст запроса. Публикуется для EXPLAIN в тесте, поэтому собирается в одном месте. */
    static String sql(boolean near, boolean hasTo) {
        return """
                SELECT t.id, t.direction, t.departure_from, t.departure_to, p.code, p.name_ky, p.name_ru, p.name_en,
                       ST_Y(p.location::geometry), ST_X(p.location::geometry), t.seats_available, t.price_kgs, t.currency,
                       %s
                FROM trips t
                JOIN pickup_locations p ON p.id = t.pickup_location_id
                %s
                WHERE t.status = 'PUBLISHED'
                  AND t.direction = :dir
                  AND t.seats_available >= :seats
                  AND t.departure_to > :now
                  AND t.departure_to >= :from
                  %s
                ORDER BY t.departure_from, %s t.id
                LIMIT :limit OFFSET :offset
                """.formatted(
                near ? "ST_Distance(p.location, r.location)" : "NULL::double precision",
                near ? "JOIN pickup_locations r ON r.id = :ref AND ST_DWithin(p.location, r.location, :radius)" : "",
                hasTo ? "AND t.departure_from <= :to" : "",
                near ? "ST_Distance(p.location, r.location)," : "");
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, "error.search." + code.toLowerCase());
    }

    private static OffsetDateTime utc(Instant t) {
        return OffsetDateTime.ofInstant(t, ZoneOffset.UTC);
    }
}
