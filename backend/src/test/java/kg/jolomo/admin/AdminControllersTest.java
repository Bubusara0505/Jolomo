package kg.jolomo.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP04-01: cookie администратора, единый отказ при входе, проверка прав на каждом маршруте. */
class AdminControllersTest {

    private static final UUID ID = UUID.randomUUID();

    private final AdminService admins = mock(AdminService.class);
    private final AdminCookies cookies = new AdminCookies(new AdminSettings(Duration.ofMinutes(30), 5, Duration.ofMinutes(15), 1000, true));
    private final AdminAuth auth = new AdminAuth(admins);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AdminAuthController(admins, auth, cookies), new AdminAccountController(admins, auth))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private static final String LOGIN_JSON = "{\"login\":\"owner\",\"password\":\"p4ssw0rd-long-enough\",\"code\":\"123456\"}";
    private static final String CREATE_JSON = "{\"login\":\"rev1\",\"displayName\":\"Rev\",\"role\":\"REVIEWER\",\"reasonCode\":\"NEW_STAFF\"}";

    private void sessionAs(AdminRole role) {
        when(admins.authenticate("TOKEN"))
                .thenReturn(Optional.of(new AdminService.AdminPrincipal(ID, "someone", "Someone", role)));
    }

    @Test
    void loginSetsStrictHttpOnlySecureCookieOnAdminPathOnly() throws Exception {
        var principal = new AdminService.AdminPrincipal(ID, "owner", "Owner", AdminRole.OWNER);
        when(admins.login("owner", "p4ssw0rd-long-enough", "123456"))
                .thenReturn(Optional.of(new AdminService.LoginSession(principal, "TOKEN", Duration.ofMinutes(30), 8)));

        var result = mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin.role").value("OWNER"))
                .andExpect(jsonPath("$.recoveryCodesLeft").value(8))
                .andReturn();

        List<String> set = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        String admin = set.stream().filter(c -> c.startsWith("jl_admin=")).findFirst().orElseThrow();
        assertThat(admin).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/api/v1/admin");
        assertThat(set.stream().filter(c -> c.startsWith("jl_csrf=")).findFirst().orElseThrow()).doesNotContain("HttpOnly");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("TOKEN");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    @Test
    void anyLoginFailureLooksTheSameAndSetsNoCookie() throws Exception {
        when(admins.login(any(), any(), any())).thenReturn(Optional.empty());

        var result = mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_LOGIN_FAILED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void loginWithoutSecondFactorFieldIsRejectedAsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"owner\",\"password\":\"p4ssw0rd-long-enough\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(admins);
    }

    @Test
    void userCookiesDoNotOpenAdminRoutes() throws Exception {
        mvc.perform(get("/api/v1/admin/auth/me").cookie(new Cookie("jl_access", "USER-ACCESS")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHENTICATED"));
    }

    @Test
    void meAndLogoutWorkWithAdminSession() throws Exception {
        sessionAs(AdminRole.REVIEWER);

        mvc.perform(get("/api/v1/admin/auth/me").cookie(new Cookie("jl_admin", "TOKEN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("REVIEWER"));

        var result = mvc.perform(post("/api/v1/admin/auth/logout").cookie(new Cookie("jl_admin", "TOKEN")))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2)
                .allSatisfy(c -> assertThat(c).contains("Max-Age=0"));
    }

    @Test
    void accountManagementIsClosedToAnonymousAndNonOwners() throws Exception {
        mvc.perform(post("/api/v1/admin/accounts").contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isUnauthorized());

        sessionAs(AdminRole.REVIEWER);
        mvc.perform(post("/api/v1/admin/accounts").cookie(new Cookie("jl_admin", "TOKEN"))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
        mvc.perform(post("/api/v1/admin/accounts/" + UUID.randomUUID() + "/disable").cookie(new Cookie("jl_admin", "TOKEN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"LEFT_TEAM\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/accounts/" + UUID.randomUUID() + "/reset").cookie(new Cookie("jl_admin", "TOKEN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"LOST_DEVICE\"}"))
                .andExpect(status().isForbidden());

        verify(admins, org.mockito.Mockito.times(3)).recordDenied(any(), eq(AdminPermission.ADMIN_MANAGE));
        verify(admins, org.mockito.Mockito.never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void ownerCreatesAccountAndSecretsAreNotCached() throws Exception {
        sessionAs(AdminRole.OWNER);
        when(admins.create("rev1", "Rev", AdminRole.REVIEWER, ID, "NEW_STAFF"))
                .thenReturn(new AdminService.Enrollment(UUID.randomUUID(), "rev1", "pw", "SECRET", "otpauth://x", List.of("AAAA-BBBB-CCCC-DDDD")));

        var result = mvc.perform(post("/api/v1/admin/accounts").cookie(new Cookie("jl_admin", "TOKEN"))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totpSecret").value("SECRET"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }
}
