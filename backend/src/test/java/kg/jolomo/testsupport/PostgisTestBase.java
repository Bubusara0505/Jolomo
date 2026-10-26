package kg.jolomo.testsupport;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Один настоящий PostGIS на весь прогон тестов. H2 не используется. */
public abstract class PostgisTestBase {

    private static final PostgreSQLContainer POSTGIS = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    private static final DataSource DATA_SOURCE;

    static {
        POSTGIS.start();
        DATA_SOURCE = new DriverManagerDataSource(
                POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword());
        Flyway.configure().dataSource(DATA_SOURCE).locations("classpath:db/migration").load().migrate();
    }

    protected final JdbcClient jdbc = JdbcClient.create(DATA_SOURCE);
    protected final TransactionOperations tx =
            new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));

    @BeforeEach
    protected void cleanTables() {
        jdbc.sql("TRUNCATE sms_messages, auth_challenges").update();
    }
}
