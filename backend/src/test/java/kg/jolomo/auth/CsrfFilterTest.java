package kg.jolomo.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import kg.jolomo.shared.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** SP02-04: CSRF на мутациях с cookie сессии. */
class CsrfFilterTest {

    @RestController
    static class Probe {
        @PostMapping("/probe")
        String mutate() {
            return "ok";
        }

        @PostMapping("/api/v1/deliveries/x/verify")
        String recipient() {
            return "ok";
        }

        @GetMapping("/probe")
        String read() {
            return "ok";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .addFilters(new CorrelationIdFilter(), new CsrfFilter())
            .build();

    @Test
    void mutationWithSessionCookieAndNoHeaderIsRejectedWithEnvelope() throws Exception {
        mvc.perform(post("/probe").cookie(new Cookie("jl_access", "x"), new Cookie("jl_csrf", "t1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(jsonPath("$.message").value("error.csrf"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void adminCookieAlsoRequiresCsrf() throws Exception {
        mvc.perform(post("/probe").cookie(new Cookie("jl_admin", "x"), new Cookie("jl_csrf", "t1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/probe")
                        .cookie(new Cookie("jl_admin", "x"), new Cookie("jl_csrf", "t1"))
                        .header("X-CSRF-Token", "t1"))
                .andExpect(status().isOk());
    }

    @Test
    void recipientRoutesDoNotUseTheSessionSoCookiesDoNotRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/deliveries/x/verify").cookie(new Cookie("jl_access", "x"), new Cookie("jl_admin", "y")))
                .andExpect(status().isOk());
    }

    @Test
    void mismatchedHeaderIsRejected() throws Exception {
        mvc.perform(post("/probe")
                        .cookie(new Cookie("jl_refresh", "x"), new Cookie("jl_csrf", "t1"))
                        .header("X-CSRF-Token", "other"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingCsrfCookieIsRejected() throws Exception {
        mvc.perform(post("/probe").cookie(new Cookie("jl_access", "x")).header("X-CSRF-Token", "t1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void matchingHeaderAndCookiePasses() throws Exception {
        mvc.perform(post("/probe")
                        .cookie(new Cookie("jl_access", "x"), new Cookie("jl_csrf", "t1"))
                        .header("X-CSRF-Token", "t1"))
                .andExpect(status().isOk());
    }

    @Test
    void requestsWithoutSessionCookieAndSafeMethodsPass() throws Exception {
        mvc.perform(post("/probe")).andExpect(status().isOk());
        mvc.perform(get("/probe").cookie(new Cookie("jl_access", "x"))).andExpect(status().isOk());
    }
}
