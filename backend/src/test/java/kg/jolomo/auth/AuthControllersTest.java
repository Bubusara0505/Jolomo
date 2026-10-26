package kg.jolomo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP02-04: verify выдаёт сессию только в cookie; refresh, logout, csrf и me. Сервисы подменены. */
class AuthControllersTest {

    private static final String PHONE = "+996555000000";

    private final OtpService otp = mock(OtpService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final SessionCookies cookies = new SessionCookies(
            new SessionSettings("test-session-secret-0123456789abcdef", Duration.ofMinutes(15), Duration.ofDays(30), true));

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new OtpController(otp, sessions, cookies), new SessionController(sessions, cookies), new MeController(sessions))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private static SessionService.Tokens tokens(UUID user) {
        return new SessionService.Tokens(user, "ACCESS", "REFRESH", Duration.ofMinutes(15), Duration.ofDays(30));
    }

    @Test
    void verifyIssuesSessionOnlyInCookies() throws Exception {
        UUID challenge = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        when(otp.verify(eq(challenge), eq(PHONE), eq(Purpose.LOGIN), eq("123456"))).thenReturn(OtpService.VerifyResult.OK);
        when(sessions.startSession(PHONE)).thenReturn(tokens(user));

        var result = mvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challenge + "\",\"phone\":\"" + PHONE
                                + "\",\"purpose\":\"LOGIN\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.userId").value(user.toString()))
                .andReturn();

        List<String> set = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(set).hasSize(3);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ACCESS").doesNotContain("REFRESH");
    }

    @Test
    void wrongCodeGivesNoCookies() throws Exception {
        when(otp.verify(any(), any(), any(), any())).thenReturn(OtpService.VerifyResult.INVALID);

        var result = mvc.perform(post("/api/v1/auth/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + UUID.randomUUID() + "\",\"phone\":\"" + PHONE
                                + "\",\"purpose\":\"LOGIN\",\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"))
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void refreshRotatesCookies() throws Exception {
        when(sessions.refresh("OLD"))
                .thenReturn(new SessionService.RefreshResult(SessionService.RefreshOutcome.OK, tokens(UUID.randomUUID())));

        var result = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("jl_refresh", "OLD")))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(3);
    }

    @Test
    void reusedRefreshGivesEnvelopeAndClearsCookies() throws Exception {
        when(sessions.refresh("STOLEN"))
                .thenReturn(new SessionService.RefreshResult(SessionService.RefreshOutcome.REUSED, null));

        var result = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("jl_refresh", "STOLEN")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REUSED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .hasSize(3)
                .allSatisfy(c -> assertThat(c).contains("Max-Age=0"));
    }

    @Test
    void logoutClearsCookies() throws Exception {
        var result = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("jl_refresh", "R")))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(3);
    }

    @Test
    void csrfEndpointSetsCookieOnlyWhenMissing() throws Exception {
        var fresh = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        assertThat(fresh.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);

        var existing = mvc.perform(get("/api/v1/auth/csrf").cookie(new Cookie("jl_csrf", "abc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csrfToken").value("abc"))
                .andReturn();
        assertThat(existing.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void meRequiresValidAccessCookie() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        UUID user = UUID.randomUUID();
        when(sessions.authenticate("GOOD")).thenReturn(java.util.Optional.of(new SessionService.Principal(user, UUID.randomUUID())));
        when(sessions.me(user)).thenReturn(java.util.Optional.of(new SessionService.MeView(user, PHONE)));

        mvc.perform(get("/api/v1/me").cookie(new Cookie("jl_access", "GOOD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(PHONE));
    }
}
