package kg.jolomo.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** SP01-03: настоящий PostGIS в контейнере. H2 не используется. */
@Testcontainers
class PostgisMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    @Test
    void firstRunAppliesMigrationsAndEnablesPostgis() throws Exception {
        var result = flyway().migrate();
        assertThat(result.success).isTrue();

        try (Connection c = DriverManager.getConnection(
                        POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword());
                ResultSet rs = c.createStatement().executeQuery("SELECT PostGIS_Version()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNotBlank();
        }
    }

    @Test
    void secondRunChangesNothing() {
        flyway().migrate();
        var second = flyway().migrate();
        assertThat(second.migrationsExecuted).isZero();
    }
}
