package kg.jolomo.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** SP02-01: один конверт ошибки и correlation id. Без Spring Boot контекста, только MVC. */
class ErrorEnvelopeTest {

    @RestController
    static class Probe {
        record Body(@NotBlank String name) {}

        @GetMapping("/probe/domain")
        String domain() {
            throw new ApiException(HttpStatus.CONFLICT, "PROBE_CONFLICT", "error.probe.conflict");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("секретная внутренняя деталь");
        }

        @PostMapping("/probe/validate")
        String validate(@Valid @RequestBody Body body) {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    @Test
    void domainErrorUsesEnvelopeAndEchoesSafeCorrelationId() throws Exception {
        mvc.perform(get("/probe/domain").header("X-Correlation-Id", "trace-12345678"))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Correlation-Id", "trace-12345678"))
                .andExpect(jsonPath("$.code").value("PROBE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("error.probe.conflict"))
                .andExpect(jsonPath("$.correlationId").value("trace-12345678"));
    }

    @Test
    void unsafeIncomingCorrelationIdIsReplaced() throws Exception {
        mvc.perform(get("/probe/domain").header("X-Correlation-Id", "bad id\nwith junk"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").value(org.hamcrest.Matchers.not("bad id\nwith junk")));
    }

    @Test
    void unexpectedErrorLeaksNothing() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("error.internal"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("секретная"))));
    }

    @Test
    void validationErrorUsesEnvelope() throws Exception {
        mvc.perform(post("/probe/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
