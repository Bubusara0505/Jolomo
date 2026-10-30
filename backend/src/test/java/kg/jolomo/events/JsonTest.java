package kg.jolomo.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Запись JSON для событий и выгрузки: объекты, массивы, числа, null и экранирование. */
class JsonTest {

    @Test
    void writesNestedObjectsAndArrays() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("a", 1);
        inner.put("b", null);
        List<Object> list = new ArrayList<>();
        list.add(inner);
        list.add("x");
        list.add(0.5);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("items", list);
        doc.put("empty", List.of());
        doc.put("ok", true);

        assertThat(Json.write(doc)).isEqualTo("{\"items\":[{\"a\":1,\"b\":null},\"x\",0.5],\"empty\":[],\"ok\":true}");
    }

    @Test
    void escapesQuotesBackslashesAndControlCharactersInsideArrays() {
        assertThat(Json.write(List.of("a\"b", "c\\d", "e\nf"))).isEqualTo("[\"a\\\"b\",\"c\\\\d\",\"e\\nf\"]");
    }
}
