package kg.jolomo.trips;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.Test;

/**
 * SP05-02: представительный EXPLAIN поискового запроса. Тест наполняет таблицу (большинство строк не подходит: чужие
 * статусы, прошлые даты, другое направление), выполняет EXPLAIN (ANALYZE) и пишет план в build/explain/trip-search.txt.
 * Файл копируется в docs/ и коммитится как запись плана. Форму плана тест не утверждает: она зависит от версии PostgreSQL.
 */
class TripSearchExplainTest extends PostgisTestBase {

    private static final int ROWS = 20_000;

    @Test
    void writesRepresentativeExplain() throws Exception {
        MutableClock clock = new MutableClock();
        UUID driver = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000081', 'ACTIVE', now())")
                .param("id", driver)
                .update();
        UUID west = jdbc.sql("SELECT id FROM pickup_locations WHERE code = 'BISHKEK_WEST_BUS_STATION'").query(UUID.class).single();
        Instant now = clock.instant();
        // Каждая 20-я поездка подходит (опубликована, будущая, нужное направление); остальные нет.
        jdbc.sql("""
                        INSERT INTO trips (id, driver_user_id, direction, pickup_location_id, departure_from, departure_to,
                                           seats_total, seats_available, price_kgs, status, created_at, updated_at, published_at)
                        SELECT gen_random_uuid(), :d,
                               CASE WHEN g % 2 = 0 THEN 'BISHKEK_TO_KARAKOL' ELSE 'KARAKOL_TO_BISHKEK' END,
                               CASE WHEN g % 2 = 0 THEN :west ELSE (SELECT id FROM pickup_locations WHERE code = 'KARAKOL_BUS_STATION') END,
                               :now + (g - 10000) * interval '7 minutes', :now + (g - 10000) * interval '7 minutes' + interval '2 hours',
                               4, 4, 700,
                               CASE WHEN g % 10 < 3 THEN 'PUBLISHED' ELSE 'CANCELLED' END,
                               :now, :now, :now
                        FROM generate_series(1, :n) g
                        """)
                .param("d", driver)
                .param("west", west)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("n", ROWS)
                .update();
        jdbc.sql("ANALYZE trips").update();
        jdbc.sql("ANALYZE pickup_locations").update();

        String sql = TripSearchService.sql(true, true)
                .replace(":dir", "'BISHKEK_TO_KARAKOL'")
                .replace(":now", "'" + now + "'::timestamptz")
                .replace(":from", "'" + now + "'::timestamptz")
                .replace(":to", "'" + now.plusSeconds(7 * 86400) + "'::timestamptz")
                .replace(":seats", "1")
                .replace(":limit", "20")
                .replace(":offset", "0")
                .replace(":ref", "'" + west + "'::uuid")
                .replace(":radius", "5000");

        List<String> plan = tx.execute(status ->
                jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql).query(String.class).list());

        Path out = Path.of("build", "explain", "trip-search.txt");
        Files.createDirectories(out.getParent());
        String header = "-- SP05-02: EXPLAIN (ANALYZE, BUFFERS) поиска поездок, " + ROWS + " строк в trips, PostGIS 16-3.4\n"
                + "-- Параметры: направление BISHKEK_TO_KARAKOL, окно 7 дней вперёд, от точки Западного автовокзала, радиус 5 км\n\n";
        Files.writeString(out, header + String.join("\n", plan) + "\n", StandardCharsets.UTF_8);

        assertThat(plan).isNotEmpty();
        assertThat(String.join("\n", plan)).contains("trips");
    }
}
