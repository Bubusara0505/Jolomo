package kg.jolomo.verification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
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

/** SP11-01: приостановка водителя. Права по ролям и обязательный код причины. */
class AdminDriverControllerTest {

    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final String BODY = "{\"reasonCode\":\"SUSPICIOUS_ACTIVITY\"}";

    private final AdminService admins = mock(AdminService.class);
    private final DriverProfileService profiles = mock(DriverProfileService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminDriverController(new AdminAuth(admins), profiles))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private void sessionAs(AdminRole role) {
        when(admins.authenticate("T")).thenReturn(Optional.of(new AdminService.AdminPrincipal(ADMIN, "x", "X", role)));
    }

    private void both(java.util.function.Consumer<String> action) {
        action.accept("/api/v1/admin/drivers/" + DRIVER + "/suspend");
        action.accept("/api/v1/admin/drivers/" + DRIVER + "/lift-suspension");
    }

    @Test
    void anonymousAndReviewerAreRefusedOnBothRoutes() {
        both(url -> {
            try {
                mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        sessionAs(AdminRole.REVIEWER);
        both(url -> {
            try {
                mvc.perform(post(url).cookie(new Cookie("jl_admin", "T")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                        .andExpect(status().isForbidden());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        verify(admins, times(2)).recordDenied(any(), eq(AdminPermission.DRIVER_SUSPEND));
        verify(profiles, never()).suspend(any(), any(), any());
        verify(profiles, never()).liftSuspension(any(), any(), any());
    }

    @Test
    void supportAndOwnerSuspendAndLiftWithTheirOwnIdAndReason() throws Exception {
        for (AdminRole role : new AdminRole[] {AdminRole.SUPPORT, AdminRole.OWNER}) {
            sessionAs(role);
            mvc.perform(post("/api/v1/admin/drivers/" + DRIVER + "/suspend").cookie(new Cookie("jl_admin", "T"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isNoContent());
            mvc.perform(post("/api/v1/admin/drivers/" + DRIVER + "/lift-suspension").cookie(new Cookie("jl_admin", "T"))
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isNoContent());
        }

        verify(profiles, times(2)).suspend(DRIVER, ADMIN, "SUSPICIOUS_ACTIVITY");
        verify(profiles, times(2)).liftSuspension(DRIVER, ADMIN, "SUSPICIOUS_ACTIVITY");
    }

    @Test
    void reasonCodeIsMandatoryInTheBody() throws Exception {
        sessionAs(AdminRole.SUPPORT);

        mvc.perform(post("/api/v1/admin/drivers/" + DRIVER + "/suspend").cookie(new Cookie("jl_admin", "T"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(profiles, never()).suspend(any(), any(), any());
    }
}
