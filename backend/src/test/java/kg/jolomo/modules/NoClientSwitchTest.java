package kg.jolomo.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ни один HTTP-маршрут не включает выключатель обязательств и флаг студента-курьера. */
class NoClientSwitchTest {

    @Test
    void noRouteTouchesObligationOrCourierSwitches() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> paths = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("kg.jolomo")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            collect(AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class), paths);
            for (Method m : c.getDeclaredMethods()) {
                collect(AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class), paths);
            }
        }

        assertThat(paths).isNotEmpty();
        assertThat(paths).noneMatch(p -> p.toLowerCase().contains("obligation") || p.toLowerCase().contains("courier"));
    }

    private static void collect(RequestMapping rm, List<String> out) {
        if (rm != null) {
            out.addAll(Arrays.asList(rm.path()));
        }
    }
}
