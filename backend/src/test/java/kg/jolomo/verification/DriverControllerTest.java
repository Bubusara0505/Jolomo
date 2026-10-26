package kg.jolomo.verification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP03-03: маршруты профиля только для авторизованного субъекта, сам себе. */
class DriverControllerTest {

    private final SessionAuth auth = mock(SessionAuth.class);
    private final DriverProfileService profiles = mock(DriverProfileService.class);
    private final EligibilityService eligibility = mock(EligibilityService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DriverController(auth, profiles, eligibility))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private void anonymous() {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));
    }

    @Test
    void everyRouteRejectsAnonymous() throws Exception {
        anonymous();

        mvc.perform(post("/api/v1/drivers/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/drivers/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/drivers/me/eligibility")).andExpect(status().isUnauthorized());
    }

    @Test
    void createReturnsOwnProfile() throws Exception {
        UUID user = UUID.randomUUID();
        when(auth.requireUserId(any())).thenReturn(user);
        when(profiles.create(user)).thenReturn(new DriverProfileService.Profile(user, DriverProfileStatus.UNVERIFIED));

        mvc.perform(post("/api/v1/drivers/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNVERIFIED"));
    }

    @Test
    void missingProfileIs404AndEligibilityShowsReason() throws Exception {
        UUID user = UUID.randomUUID();
        when(auth.requireUserId(any())).thenReturn(user);
        when(profiles.find(user)).thenReturn(Optional.empty());
        when(eligibility.canPublishTrip(user))
                .thenReturn(new EligibilityService.Decision(false, EligibilityService.Reason.NOT_A_DRIVER));

        mvc.perform(get("/api/v1/drivers/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRIVER_PROFILE_NOT_FOUND"));
        mvc.perform(get("/api/v1/drivers/me/eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canPublishTrip").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_A_DRIVER"));
    }
}
