package kg.jolomo.parcels;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP08-01: HTTP-слой решений по посылкам. */
class ParcelDecisionControllerTest {

    private final SessionAuth auth = mock(SessionAuth.class);
    private final ParcelService parcels = mock(ParcelService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ParcelController(auth, parcels))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private final UUID user = UUID.randomUUID();
    private final UUID parcelId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-27T09:00:00Z");

    private ParcelService.Parcel parcel(ParcelStatus s) {
        return new ParcelService.Parcel(parcelId, UUID.randomUUID(), user, "S", "DOCUMENTS", s, at, at);
    }

    @Test
    void decisionRoutesRequireASession() throws Exception {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));

        mvc.perform(post("/api/v1/parcels/" + parcelId + "/accept")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/parcels/" + parcelId + "/reject")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/parcels/" + parcelId + "/cancel")).andExpect(status().isUnauthorized());
        verify(parcels, never()).accept(any(), any());
    }

    @Test
    void decisionRoutesCallTheServiceAsTheSignedInUser() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(parcels.accept(user, parcelId)).thenReturn(parcel(ParcelStatus.ACCEPTED));
        when(parcels.reject(user, parcelId)).thenReturn(parcel(ParcelStatus.REJECTED));
        when(parcels.cancel(user, parcelId)).thenReturn(parcel(ParcelStatus.CANCELLED));

        mvc.perform(post("/api/v1/parcels/" + parcelId + "/accept"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/v1/parcels/" + parcelId + "/reject"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/api/v1/parcels/" + parcelId + "/cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void capacityRefusalBecomesTheErrorEnvelope() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(parcels.accept(user, parcelId))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "NO_PARCEL_CAPACITY", "error.parcel.no_capacity"));

        mvc.perform(post("/api/v1/parcels/" + parcelId + "/accept"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_PARCEL_CAPACITY"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
