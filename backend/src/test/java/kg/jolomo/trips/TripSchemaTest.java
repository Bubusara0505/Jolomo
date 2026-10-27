package kg.jolomo.trips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** SP04-04: ограничения базы. Опубликованная поездка всегда имеет окно, точку из каталога, вместимость и цену в KGS. */
class TripSchemaTest extends PostgisTestBase {

    private UUID driver;
    private UUID bishkekPoint;
    private UUID karakolPoint;

    @BeforeEach
    void setUp() {
        driver = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000050', 'ACTIVE', now())")
                .param("id", driver)
                .update();
        bishkekPoint = point("BISHKEK_WEST_BUS_STATION");
        karakolPoint = point("KARAKOL_BUS_STATION");
    }

    private UUID point(String code) {
        return jdbc.sql("SELECT id FROM pickup_locations WHERE code = :c").param("c", code).query(UUID.class).single();
    }

    /** Рабочая строка поездки; каждый тест портит одно поле. */
    private void insert(String direction, UUID pickup, String from, String to, int seats, int available, int price,
                        String currency, String status, int parcels, int parcelsAvailable) {
        jdbc.sql("""
                        INSERT INTO trips (id, driver_user_id, direction, pickup_location_id, departure_from, departure_to,
                                           seats_total, seats_available, parcel_slots_total, parcel_slots_available,
                                           price_kgs, currency, status, created_at, updated_at, published_at)
                        VALUES (:id, :d, :dir, :p, :from::timestamptz, :to::timestamptz, :seats, :avail, :parcels, :pavail,
                                :price, :cur, :status, now(), now(), CASE WHEN :status = 'PUBLISHED' THEN now() END)
                        """)
                .param("id", UUID.randomUUID())
                .param("d", driver)
                .param("dir", direction)
                .param("p", pickup)
                .param("from", from)
                .param("to", to)
                .param("seats", seats)
                .param("avail", available)
                .param("parcels", parcels)
                .param("pavail", parcelsAvailable)
                .param("price", price)
                .param("cur", currency)
                .param("status", status)
                .update();
    }

    private void insertGood() {
        insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "KGS", "PUBLISHED", 2, 2);
    }

    private void rejected(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seedHasExactlyTheTwoCatalogPointsInThreeLanguages() {
        var rows = jdbc.sql("SELECT code, city, name_ky, name_ru, name_en FROM pickup_locations ORDER BY code")
                .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)})
                .list();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[0]).isEqualTo("BISHKEK_WEST_BUS_STATION");
        assertThat(rows.get(0)[1]).isEqualTo("BISHKEK");
        assertThat(rows.get(1)[0]).isEqualTo("KARAKOL_BUS_STATION");
        assertThat(rows.get(1)[1]).isEqualTo("KARAKOL");
        for (String[] r : rows) {
            assertThat(r[2]).isNotBlank();
            assertThat(r[3]).isNotBlank();
            assertThat(r[4]).isNotBlank();
        }
    }

    @Test
    void seedCoordinatesAreSaneAndSpatialQueriesWork() {
        Double km = jdbc.sql("""
                        SELECT ST_Distance(a.location, b.location) / 1000
                        FROM pickup_locations a, pickup_locations b
                        WHERE a.code = 'BISHKEK_WEST_BUS_STATION' AND b.code = 'KARAKOL_BUS_STATION'
                        """)
                .query(Double.class)
                .single();
        assertThat(km).isBetween(250.0, 400.0);

        // Защита от опечаток при замене координат: точки лежат внутри своих городских рамок.
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pickup_locations
                        WHERE (city = 'BISHKEK' AND ST_Y(location::geometry) BETWEEN 42.7 AND 43.0
                                                AND ST_X(location::geometry) BETWEEN 74.3 AND 74.8)
                           OR (city = 'KARAKOL' AND ST_Y(location::geometry) BETWEEN 42.4 AND 42.6
                                                AND ST_X(location::geometry) BETWEEN 78.3 AND 78.5)
                        """)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void wellFormedTripsInBothDirectionsAreAccepted() {
        insertGood();
        insert("KARAKOL_TO_BISHKEK", karakolPoint, "2026-11-02T06:00:00Z", "2026-11-02T08:00:00Z", 3, 3, 700, "KGS", "DRAFT", 0, 0);

        assertThat(jdbc.sql("SELECT count(*) FROM trips").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void pickupMustBelongToTheOriginCityOfTheDirection() {
        rejected(() -> insert("BISHKEK_TO_KARAKOL", karakolPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "KGS", "DRAFT", 0, 0));
        rejected(() -> insert("KARAKOL_TO_BISHKEK", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "KGS", "DRAFT", 0, 0));
    }

    @Test
    void pickupOutsideTheCatalogIsRejected() {
        rejected(() -> insert("BISHKEK_TO_KARAKOL", UUID.randomUUID(), "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "KGS", "DRAFT", 0, 0));
    }

    @Test
    void unknownDirectionIsRejected() {
        rejected(() -> insert("BISHKEK_TO_OSH", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "KGS", "DRAFT", 0, 0));
    }

    @Test
    void windowMustHavePositiveLength() {
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T08:00:00Z", "2026-11-01T06:00:00Z", 4, 4, 700, "KGS", "DRAFT", 0, 0));
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T06:00:00Z", 4, 4, 700, "KGS", "DRAFT", 0, 0));
    }

    @Test
    void capacityMustBePositiveAndAvailabilityStayWithinIt() {
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 0, 0, 700, "KGS", "DRAFT", 0, 0));
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 5, 700, "KGS", "DRAFT", 0, 0));
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, -1, 700, "KGS", "DRAFT", 0, 0));
    }

    @Test
    void parcelSlotsCannotGoBelowZeroOrAboveTotal() {
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "KGS", "DRAFT", -1, -1));
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "KGS", "DRAFT", 2, 3));
    }

    @Test
    void priceMustBePositiveAndInKgsOnly() {
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 0, "KGS", "DRAFT", 0, 0));
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, -5, "KGS", "DRAFT", 0, 0));
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "USD", "DRAFT", 0, 0));
    }

    @Test
    void unknownStatusIsRejected() {
        rejected(() -> insert("BISHKEK_TO_KARAKOL", bishkekPoint, "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z", 4, 4, 700, "KGS", "OPEN", 0, 0));
    }

    @Test
    void publishedTripWithoutPublishTimeIsRejected() {
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO trips (id, driver_user_id, direction, pickup_location_id, departure_from, departure_to,
                                                   seats_total, seats_available, price_kgs, status, created_at, updated_at)
                                VALUES (:id, :d, 'BISHKEK_TO_KARAKOL', :p, now() + interval '1 day', now() + interval '2 day',
                                        4, 4, 700, 'PUBLISHED', now(), now())
                                """)
                        .param("id", UUID.randomUUID())
                        .param("d", driver)
                        .param("p", bishkekPoint)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyRequiredColumnIsNotNull() {
        var nullable = jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_name = 'trips' AND is_nullable = 'YES' ORDER BY column_name
                        """)
                .query(String.class)
                .list();

        // Пустым может быть только время публикации.
        assertThat(nullable).containsExactlyInAnyOrder("published_at", "origin_city");
        insertGood();
    }
}
