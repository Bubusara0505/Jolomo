package kg.jolomo.trips;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP05-02: публичный маршрут поиска разбирает параметры и не показывает владельца. */
class TripSearchControllerTest {

    private final TripSearchService search = mock(TripSearchService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TripSearchController(search))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    @Test
    void anonymousSearchParsesAllParametersAndReturnsPublicFieldsOnly() throws Exception {
        UUID point = UUID.randomUUID();
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        when(search.search(any())).thenReturn(List.of(new TripSearchService.Hit(UUID.randomUUID(), Direction.BISHKEK_TO_KARAKOL,
                from, from.plusSeconds(7200), "BISHKEK_WEST_BUS_STATION", "ky", "ru", "en", 42.8, 74.5, 3, 700, "KGS", 120.5)));

        mvc.perform(get("/api/v1/trips")
                        .param("direction", "BISHKEK_TO_KARAKOL")
                        .param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-03T00:00:00Z")
                        .param("nearPickupLocationId", point.toString())
                        .param("radiusMeters", "3000")
                        .param("seats", "2")
                        .param("limit", "10")
                        .param("offset", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].priceKgs").value(700))
                .andExpect(jsonPath("$.items[0].distanceMeters").value(120.5))
                .andExpect(jsonPath("$.items[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.offset").value(5));

        verify(search).search(argThat(q -> q.direction() == Direction.BISHKEK_TO_KARAKOL
                && q.from().equals(from)
                && q.nearPickupLocationId().equals(point)
                && q.radiusMeters() == 3000
                && q.seats() == 2
                && q.limit() == 10
                && q.offset() == 5));
    }

    @Test
    void defaultsAreReportedAndServiceErrorsUseTheEnvelope() throws Exception {
        when(search.search(any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/trips").param("direction", "KARAKOL_TO_BISHKEK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.offset").value(0));

        when(search.search(any())).thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIRECTION", "error.search.invalid_direction"));
        mvc.perform(get("/api/v1/trips"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DIRECTION"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void malformedParametersAreClientErrorsNotServerErrors() throws Exception {
        mvc.perform(get("/api/v1/trips").param("direction", "MOON")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trips").param("direction", "BISHKEK_TO_KARAKOL").param("from", "yesterday")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trips").param("direction", "BISHKEK_TO_KARAKOL").param("seats", "many")).andExpect(status().isBadRequest());
    }
}
