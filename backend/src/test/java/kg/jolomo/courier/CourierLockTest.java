package kg.jolomo.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;

/** SP12-02: база, настройки по умолчанию и описание API не дают включить студента-курьера. */
class CourierLockTest extends PostgisTestBase {

    @Test
    void theDatabaseRefusesToSwitchTheFlagOn() {
        assertThat(jdbc.sql("SELECT enabled FROM feature_flags WHERE key = 'STUDENT_COURIER'").query(Boolean.class).single()).isFalse();

        assertThatThrownBy(() -> jdbc.sql("UPDATE feature_flags SET enabled = true WHERE key = 'STUDENT_COURIER'").update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.sql("SELECT enabled FROM feature_flags WHERE key = 'STUDENT_COURIER'").query(Boolean.class).single()).isFalse();
    }

    @Test
    void defaultConfigurationHasNoCourierSetting() throws Exception {
        String yml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(yml.toLowerCase()).doesNotContain("courier");
    }

    @Test
    void theApiDescriptionHasNoCourierRouteOrSwitch() throws Exception {
        String openapi = Files.readString(Path.of("openapi.yaml"), StandardCharsets.UTF_8);

        assertThat(openapi.toLowerCase()).doesNotContain("courier").doesNotContain("feature_flag").doesNotContain("feature-flag");
    }

    @Test
    void thereIsNoSwitchEnvironmentVariableInTheTemplate() throws Exception {
        String env = Files.readString(Path.of(".env.example"), StandardCharsets.UTF_8);

        assertThat(env.toLowerCase()).doesNotContain("courier");
    }
}
