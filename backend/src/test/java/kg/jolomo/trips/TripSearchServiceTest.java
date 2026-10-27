package kg.jolomo.trips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.trips.TripSearchService.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP05-02: поиск на настоящем PostGIS. Исключает просроченные, полные и чужого направления. */
class TripSearchServiceTest extends PostgisTestBase {

    private final MutableClock clock = new MutableClock();
    private TripSearchService search;
    private UUID driver;
    private UUID westStation;
    private UUID karakolStation;

    @BeforeEach
    void setUp() {
        search = new TripSearchService(jdbc, clock);
        driver = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000080', 'ACTIVE', now())")
                .param("id", driver)
                .update();
        westStation = point("BISHKEK_WEST_BUS_STATION");
        karakolStation = point("KARAKOL_BUS_STATION");
    }

    /** Дополнительные точки теста не должны попасть в каталог других тестов. */
    @AfterEach
    void removeExtraPoints() {
        jdbc.sql("DELETE FROM trips").update();
        jdbc.sql("DELETE FROM pickup_locations WHERE code LIKE 'TEST_%'").update();
    }

    private UUID point(String code) {
        return jdbc.sql("SELECT id FROM pickup_locations WHERE code = :c").param("c", code).query(UUID.class).single();
    }

    /** Дополнительная точка Бишкека в заданных метрах к северу от западного вокзала (1 градус широты примерно 111 км). */
    private UUID extraPoint(String code, double metersNorth) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO pickup_locations (id, code, city, name_ky, name_ru, name_en, location)
                        SELECT :id, :code, 'BISHKEK', 'ky', 'ru', 'en',
                               ST_SetSRID(ST_MakePoint(ST_X(location::geometry), ST_Y(location::geometry) + :deg), 4326)::geography
                        FROM pickup_locations WHERE code = 'BISHKEK_WEST_BUS_STATION'
                        """)
                .param("id", id)
                .param("code", code)
                .param("deg", metersNorth / 111_000.0)
                .update();
        return id;
    }

    private UUID trip(Direction dir, UUID pickup, Duration fromNow, Duration length, int seatsTotal, int seatsAvailable, String status, int price) {
        UUID id = UUID.randomUUID();
        Instant from = clock.instant().plus(fromNow);
        jdbc.sql("""
                        INSERT INTO trips (id, driver_user_id, direction, pickup_location_id, departure_from, departure_to,
                                           seats_total, seats_available, price_kgs, status, created_at, updated_at, published_at)
                        VALUES (:id, :d, :dir, :p, :from, :to, :st, :sa, :price, :status, :now, :now,
                                CASE WHEN :status = 'PUBLISHED' THEN :now END)
                        """)
                .param("id", id)
                .param("d", driver)
                .param("dir", dir.name())
                .param("p", pickup)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(from.plus(length), ZoneOffset.UTC))
                .param("st", seatsTotal)
                .param("sa", seatsAvailable)
                .param("price", price)
                .param("status", status)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID ok(Duration fromNow) {
        return trip(Direction.BISHKEK_TO_KARAKOL, westStation, fromNow, Duration.ofHours(2), 4, 4, "PUBLISHED", 700);
    }

    private Query q(Direction dir) {
        return new Query(dir, null, null, null, null, null, null, null);
    }

    private List<UUID> ids(List<TripSearchService.Hit> hits) {
        return hits.stream().map(TripSearchService.Hit::id).toList();
    }

    private void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void returnsOnlyPublishedUpcomingTripsOfTheRequestedDirectionWithFreeSeats() {
        UUID good = ok(Duration.ofDays(1));
        trip(Direction.BISHKEK_TO_KARAKOL, westStation, Duration.ofDays(1), Duration.ofHours(2), 4, 4, "DRAFT", 700);
        trip(Direction.BISHKEK_TO_KARAKOL, westStation, Duration.ofDays(1), Duration.ofHours(2), 4, 4, "CANCELLED", 700);
        trip(Direction.BISHKEK_TO_KARAKOL, westStation, Duration.ofDays(1), Duration.ofHours(2), 4, 4, "COMPLETED", 700);
        trip(Direction.BISHKEK_TO_KARAKOL, westStation, Duration.ofDays(1), Duration.ofHours(2), 4, 0, "PUBLISHED", 700); // полная
        trip(Direction.BISHKEK_TO_KARAKOL, westStation, Duration.ofDays(-2), Duration.ofHours(2), 4, 4, "PUBLISHED", 700); // просрочена
        trip(Direction.KARAKOL_TO_BISHKEK, karakolStation, Duration.ofDays(1), Duration.ofHours(2), 4, 4, "PUBLISHED", 700); // другое направление

        assertThat(ids(search.search(q(Direction.BISHKEK_TO_KARAKOL)))).containsExactly(good);
        assertThat(search.search(q(Direction.KARAKOL_TO_BISHKEK))).hasSize(1);
    }

    @Test
    void tripWhoseDepartureWindowIsStillOpenCountsAsUpcoming() {
        UUID running = trip(Direction.BISHKEK_TO_KARAKOL, westStation, Duration.ofHours(-1), Duration.ofHours(3), 4, 4, "PUBLISHED", 700);

        assertThat(ids(search.search(q(Direction.BISHKEK_TO_KARAKOL)))).containsExactly(running);
        clock.advance(Duration.ofHours(3));
        assertThat(search.search(q(Direction.BISHKEK_TO_KARAKOL))).isEmpty();
    }

    @Test
    void seatsParameterRequiresEnoughFreePlaces() {
        UUID two = trip(Direction.BISHKEK_TO_KARAKOL, westStation, Duration.ofDays(1), Duration.ofHours(2), 4, 2, "PUBLISHED", 700);
        UUID four = trip(Direction.BISHKEK_TO_KARAKOL, westStation, Duration.ofDays(2), Duration.ofHours(2), 4, 4, "PUBLISHED", 700);

        assertThat(ids(search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, null, null, 1, null, null))))
                .containsExactly(two, four);
        assertThat(ids(search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, null, null, 3, null, null))))
                .containsExactly(four);
    }

    @Test
    void departureWindowSelectsOverlappingTrips() {
        UUID day1 = ok(Duration.ofDays(1));
        UUID day3 = ok(Duration.ofDays(3));
        UUID day5 = ok(Duration.ofDays(5));
        Instant now = clock.instant();

        var hits = search.search(new Query(Direction.BISHKEK_TO_KARAKOL, now.plus(Duration.ofDays(2)), now.plus(Duration.ofDays(4)),
                null, null, null, null, null));

        assertThat(ids(hits)).containsExactly(day3);
        assertThat(ids(search.search(new Query(Direction.BISHKEK_TO_KARAKOL, now.plus(Duration.ofDays(2)), null, null, null, null, null, null))))
                .containsExactly(day3, day5);
        assertThat(day1).isNotNull();
    }

    @Test
    void distanceFilterUsesMetersFromACatalogPointAndSortsNearestFirstWithinTheSameDeparture() {
        UUID near = extraPoint("TEST_NEAR", 2_000);
        UUID far = extraPoint("TEST_FAR", 30_000);
        UUID atStation = ok(Duration.ofDays(1));
        UUID nearTrip = trip(Direction.BISHKEK_TO_KARAKOL, near, Duration.ofDays(1), Duration.ofHours(2), 4, 4, "PUBLISHED", 700);
        trip(Direction.BISHKEK_TO_KARAKOL, far, Duration.ofDays(1), Duration.ofHours(2), 4, 4, "PUBLISHED", 700);
        // у всех трёх одинаковое время отправления: порядок задаёт расстояние

        var hits = search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, westStation, 5_000, null, null, null));

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).id()).isEqualTo(atStation);
        assertThat(hits.get(0).distanceMeters()).isLessThan(1.0);
        assertThat(hits.get(1).id()).isEqualTo(nearTrip);
        assertThat(hits.get(1).distanceMeters()).isBetween(1_900.0, 2_100.0);

        var wide = search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, westStation, 40_000, null, null, null));
        assertThat(wide).hasSize(3);
    }

    @Test
    void withoutReferencePointDistanceIsAbsent() {
        ok(Duration.ofDays(1));
        assertThat(search.search(q(Direction.BISHKEK_TO_KARAKOL)).get(0).distanceMeters()).isNull();
    }

    @Test
    void resultsAreOrderedByDepartureAndPaged() {
        UUID a = ok(Duration.ofDays(3));
        UUID b = ok(Duration.ofDays(1));
        UUID c = ok(Duration.ofDays(2));

        assertThat(ids(search.search(q(Direction.BISHKEK_TO_KARAKOL)))).containsExactly(b, c, a);
        assertThat(ids(search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, null, null, null, 2, 0)))).containsExactly(b, c);
        assertThat(ids(search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, null, null, null, 2, 2)))).containsExactly(a);
    }

    @Test
    void hitCarriesOnlyPublicFields() {
        ok(Duration.ofDays(1));

        var hit = search.search(q(Direction.BISHKEK_TO_KARAKOL)).get(0);

        assertThat(hit.pickupNameRu()).isEqualTo("Западный автовокзал Бишкека");
        assertThat(hit.priceKgs()).isEqualTo(700);
        assertThat(hit.currency()).isEqualTo("KGS");
        assertThat(hit.seatsAvailable()).isEqualTo(4);
        assertThat(hit.toString()).doesNotContain(driver.toString());
    }

    @Test
    void badParametersAreRejectedWithAStableCode() {
        UUID west = westStation;
        Instant now = clock.instant();

        expectCode(() -> search.search(null), "INVALID_DIRECTION");
        expectCode(() -> search.search(new Query(null, null, null, null, null, null, null, null)), "INVALID_DIRECTION");
        expectCode(() -> search.search(new Query(Direction.BISHKEK_TO_KARAKOL, now, now, null, null, null, null, null)), "INVALID_WINDOW");
        expectCode(() -> search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, null, null, 0, null, null)), "INVALID_SEATS");
        expectCode(() -> search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, null, null, null, 0, null)), "INVALID_LIMIT");
        expectCode(() -> search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, null, null, null, 51, null)), "INVALID_LIMIT");
        expectCode(() -> search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, null, null, null, null, -1)), "INVALID_OFFSET");
        expectCode(() -> search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, west, 0, null, null, null)), "INVALID_RADIUS");
        expectCode(() -> search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, west, 100_001, null, null, null)), "INVALID_RADIUS");
        expectCode(() -> search.search(new Query(Direction.BISHKEK_TO_KARAKOL, null, null, UUID.randomUUID(), null, null, null, null)),
                "PICKUP_NOT_IN_CATALOG");
    }
}
