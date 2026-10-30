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

/** SP04-03: legal hold только у владельца. Остальным отказ и запись отказа в аудит. */
class AdminLegalHoldControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID HOLD = UUID.randomUUID();
    private static final String BODY = "{\"userId\":\"" + USER + "\",\"reasonCode\":\"LEGAL_REQUEST_1\"}";

    private final AdminService admins = mock(AdminService.class);
    private final LegalHoldService holds = mock(LegalHoldService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminLegalHoldController(new AdminAuth(admins), holds))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private void sessionAs(AdminRole role) {
        when(admins.authenticate("T")).thenReturn(Optional.of(new AdminService.AdminPrincipal(ADMIN, "x", "X", role)));
    }

    @Test
    void anonymousIsUnauthorizedAndSupportAndReviewerAreForbidden() throws Exception {
        mvc.perform(post("/api/v1/admin/legal-holds").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        for (AdminRole role : new AdminRole[] {AdminRole.SUPPORT, AdminRole.REVIEWER}) {
            sessionAs(role);
            mvc.perform(post("/api/v1/admin/legal-holds").cookie(new Cookie("jl_admin", "T"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/admin/legal-holds/" + HOLD + "/release").cookie(new Cookie("jl_admin", "T"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"REQUEST_WITHDRAWN\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/legal-holds").param("userId", USER.toString()).cookie(new Cookie("jl_admin", "T")))
                    .andExpect(status().isForbidden());
        }

        verify(admins, org.mockito.Mockito.times(6)).recordDenied(any(), eq(AdminPermission.LEGAL_HOLD));
        verifyNoInteractions(holds);
    }

    @Test
    void ownerPlacesReleasesAndListsHolds() throws Exception {
        sessionAs(AdminRole.OWNER);
        var placed = new LegalHoldService.Hold(HOLD, USER, "LEGAL_REQUEST_1", ADMIN, Instant.parse("2026-11-01T10:00:00Z"), null, null);
        var released = new LegalHoldService.Hold(HOLD, USER, "LEGAL_REQUEST_1", ADMIN, Instant.parse("2026-11-01T10:00:00Z"), ADMIN,
                Instant.parse("2026-11-02T10:00:00Z"));
        when(holds.place(ADMIN, USER, "LEGAL_REQUEST_1")).thenReturn(placed);
        when(holds.release(ADMIN, HOLD, "REQUEST_WITHDRAWN")).thenReturn(released);
        when(holds.list(USER)).thenReturn(List.of(released));

        mvc.perform(post("/api/v1/admin/legal-holds").cookie(new Cookie("jl_admin", "T"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(USER.toString()));
        mvc.perform(post("/api/v1/admin/legal-holds/" + HOLD + "/release").cookie(new Cookie("jl_admin", "T"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"REQUEST_WITHDRAWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedBy").value(ADMIN.toString()));
        mvc.perform(get("/api/v1/admin/legal-holds").param("userId", USER.toString()).cookie(new Cookie("jl_admin", "T")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(HOLD.toString()));
    }

    @Test
    void incompleteBodyIsABadRequest() throws Exception {
        sessionAs(AdminRole.OWNER);

        mvc.perform(post("/api/v1/admin/legal-holds").cookie(new Cookie("jl_admin", "T"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + USER + "\"}"))
                .andExpect(status().isBadRequest());

        verify(holds, never()).place(any(), any(), any());
    }
}
