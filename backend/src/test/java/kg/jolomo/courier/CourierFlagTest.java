package kg.jolomo.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** SP12-02 (RQ-13): флаг студента-курьера нельзя включить конфигурацией, окружением или кодом. */
class CourierFlagTest {

    @Test
    void flagIsOffAndNoConfigurationIsNeeded() {
        CourierFlag flag = new CourierFlag(new MockEnvironment());

        assertThat(flag.enabled()).isFalse();
    }

    @Test
    void anyAttemptToSwitchItOnThroughConfigurationStopsStartup() {
        String[][] attempts = {
                {"jolomo.courier.enabled", "true"},
                {"JOLOMO_COURIER_ENABLED", "1"},
                {"courier.enabled", "yes"},
                {"COURIER_ENABLED", "true"},
                {"jolomo.courier-enabled", "on"},
                {"jolomo.courier.student.enabled", "true"},
        };
        for (String[] a : attempts) {
            MockEnvironment env = new MockEnvironment().withProperty(a[0], a[1]);

            assertThatThrownBy(() -> new CourierFlag(env)).as(a[0] + "=" + a[1])
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Флаг студента-курьера включить нельзя");
        }
    }

    @Test
    void explicitlyOffOrEmptyOrUnrelatedSettingsAreFine() {
        assertThatCode(() -> new CourierFlag(new MockEnvironment()
                .withProperty("jolomo.courier.enabled", "false")
                .withProperty("COURIER_ENABLED", "")
                .withProperty("jolomo.delivery.enabled", "true")
                .withProperty("something.couriers.list", "true"))).doesNotThrowAnyException();
    }

    @Test
    void theFlagHasNoSwitchInCode() {
        // Единственный публичный нестатический метод это enabled(); состояния и сеттеров нет.
        assertThat(Arrays.stream(CourierFlag.class.getDeclaredMethods())
                        .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                        .map(Method::getName))
                .containsExactly("enabled");
        assertThat(Arrays.stream(CourierFlag.class.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))).isEmpty();
    }
}
