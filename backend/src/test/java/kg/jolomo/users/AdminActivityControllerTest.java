package kg.jolomo.users;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.admin.AdminAuth;
import kg.jolomo.admin.AdminPermission;
import kg.jolomo.admin.AdminRole;
import kg.jolomo.admin.AdminService;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP11-01: пометки подозрительной активности. Права по ролям. */
class AdminActivityControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final String BODY = "{\"userId\":\"" + USER + "\",\"kind\":\"MULTIPLE_ACCOUNTS\",\"reasonCode\":\"RECIPIENT_CALLED\"}";

    private final AdminService admins = mock(AdminService.class);
    private final ActivityFlagService flags = mock(ActivityFlagService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminActivityController(new AdminAuth(admins), flags))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private void sessionAs(AdminRole role) {
        when(admins.authenticate("T")).thenReturn(Optional.of(new AdminService.AdminPrincipal(ADMIN, "x", "X", role)));
    }

    @Test
    void anonymousAndReviewerAreRefused() throws Exception {
        mvc.perform(post("/api/v1/admin/suspicious-activity").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/suspicious-activity").param("userId", USER.toString())).andExpect(status().isUnauthorized());

        sessionAs(AdminRole.REVIEWER);
        mvc.perform(post("/api/v1/admin/suspicious-activity").cookie(new Cookie("jl_admin", "T"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/suspicious-activity").param("userId", USER.toString()).cookie(new Cookie("jl_admin", "T")))
                .andExpect(status().isForbidden());

        verify(admins, org.mockito.Mockito.times(2)).recordDenied(any(), eq(AdminPermission.ACTIVITY_FLAG));
        verifyNoInteractions(flags);
    }

    @Test
    void supportFlagsAndReadsBack() throws Exception {
        sessionAs(AdminRole.SUPPORT);
        ActivityFlagService.Flag saved = new ActivityFlagService.Flag(UUID.randomUUID(), USER, "MULTIPLE_ACCOUNTS",
                "RECIPIENT_CALLED", ADMIN, Instant.parse("2026-11-01T10:00:00Z"));
        when(flags.record(ADMIN, USER, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED")).thenReturn(saved);
        when(flags.list(USER, 50)).thenReturn(List.of(saved));

        mvc.perform(post("/api/v1/admin/suspicious-activity").cookie(new Cookie("jl_admin", "T"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("MULTIPLE_ACCOUNTS"));
        mvc.perform(get("/api/v1/admin/suspicious-activity").param("userId", USER.toString()).cookie(new Cookie("jl_admin", "T")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(USER.toString()));
    }

    @Test
    void incompleteBodyIsABadRequest() throws Exception {
        sessionAs(AdminRole.SUPPORT);

        mvc.perform(post("/api/v1/admin/suspicious-activity").cookie(new Cookie("jl_admin", "T"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + USER + "\"}"))
                .andExpect(status().isBadRequest());

        verify(flags, never()).record(any(), any(), any(), any());
    }
}
