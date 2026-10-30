package kg.jolomo.courier;

import java.util.Locale;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Флаг студента-курьера (SP12-02, RQ-13). В этом выпуске он жёстко выключен и ниоткуда не включается:
 * нет ни свойства, ни переменной окружения, ни маршрута API, ни записи в базе, которые что-то меняли бы
 * (в БД ограничение student_courier_stays_off). Любая попытка задать курьерскую настройку в конфигурации или окружении
 * останавливает запуск приложения: молча проигнорированный переключатель выглядел бы как работающий.
 * Включить флаг позже можно только отдельным решением (меморандумом) и изменением кода и миграции.
 */
@Component
public class CourierFlag {

    public CourierFlag(Environment environment) {
        assertNoCourierSwitch(environment);
    }

    /** Всегда false. Любой будущий курьерский код обязан спрашивать только это значение. */
    public boolean enabled() {
        return false;
    }

    static void assertNoCourierSwitch(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!isCourierName(name)) {
                    continue;
                }
                Object value = source.getProperty(name);
                String text = value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
                if (!text.isEmpty() && !text.equals("false")) {
                    throw new IllegalStateException("Флаг студента-курьера включить нельзя (настройка «" + name
                            + "»). Это возможно только по отдельному решению владельца продукта и изменением кода.");
                }
            }
        }
    }

    /** jolomo.courier.*, courier.*, а также JOLOMO_COURIER_* и COURIER_* в окружении. */
    private static boolean isCourierName(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
        return n.equals("courier") || n.startsWith("courier.") || n.startsWith("jolomo.courier");
    }
}
