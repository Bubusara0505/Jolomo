package kg.jolomo.parcels;

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

/** SP09-01: маршруты инцидентов открыты только поддержке, у них нет способа завершить доставку. */
class AdminIncidentControllerTest {

    private static final UUID PARCEL = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final String BODY = "{\"parcelId\":\"" + PARCEL + "\",\"kind\":\"PARCEL_LOST\",\"reasonCode\":\"RECIPIENT_CALLED\"}";

    private final AdminService admins = mock(AdminService.class);
    private final IncidentService incidents = mock(IncidentService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminIncidentController(new AdminAuth(admins), incidents))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private void sessionAs(String token, AdminRole role) {
        when(admins.authenticate(token)).thenReturn(Optional.of(new AdminService.AdminPrincipal(ADMIN, "x", "X", role)));
    }

    private static IncidentService.Incident sample() {
        return new IncidentService.Incident(UUID.randomUUID(), PARCEL, "PARCEL_LOST", "RECIPIENT_CALLED", ADMIN,
                "DELIVERY_PENDING", "leg-v1", Instant.parse("2026-11-01T10:00:00Z"));
    }

    @Test
    void closedToAnonymousAndToReviewers() throws Exception {
        mvc.perform(post("/api/v1/admin/incidents").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/incidents").param("parcelId", PARCEL.toString())).andExpect(status().isUnauthorized());

        sessionAs("REV", AdminRole.REVIEWER);
        mvc.perform(post("/api/v1/admin/incidents").cookie(new Cookie("jl_admin", "REV"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/incidents/" + UUID.randomUUID()).cookie(new Cookie("jl_admin", "REV")))
                .andExpect(status().isForbidden());

        verify(admins, org.mockito.Mockito.times(2)).recordDenied(any(), eq(AdminPermission.INCIDENT_RECORD));
        verifyNoInteractions(incidents);
    }

    @Test
    void supportRecordsAnIncidentAndGetsTheStoredRecordBack() throws Exception {
        sessionAs("SUP", AdminRole.SUPPORT);
        IncidentService.Incident saved = sample();
        when(incidents.record(ADMIN, PARCEL, "PARCEL_LOST", "RECIPIENT_CALLED")).thenReturn(saved);

        mvc.perform(post("/api/v1/admin/incidents").cookie(new Cookie("jl_admin", "SUP"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.termsVersion").value("leg-v1"))
                .andExpect(jsonPath("$.parcelStatus").value("DELIVERY_PENDING"));
    }

    @Test
    void incompleteBodyIsABadRequest() throws Exception {
        sessionAs("SUP", AdminRole.SUPPORT);

        mvc.perform(post("/api/v1/admin/incidents").cookie(new Cookie("jl_admin", "SUP"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parcelId\":\"" + PARCEL + "\"}"))
                .andExpect(status().isBadRequest());

        verify(incidents, never()).record(any(), any(), any(), any());
    }

    @Test
    void listAndGetWork() throws Exception {
        sessionAs("SUP", AdminRole.SUPPORT);
        IncidentService.Incident saved = sample();
        when(incidents.list(PARCEL, 50)).thenReturn(List.of(saved));
        when(incidents.find(saved.id())).thenReturn(Optional.of(saved));
        when(incidents.find(PARCEL)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/admin/incidents").param("parcelId", PARCEL.toString()).cookie(new Cookie("jl_admin", "SUP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("PARCEL_LOST"));
        mvc.perform(get("/api/v1/admin/incidents/" + saved.id()).cookie(new Cookie("jl_admin", "SUP")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/incidents/" + PARCEL).cookie(new Cookie("jl_admin", "SUP")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
    }
}
