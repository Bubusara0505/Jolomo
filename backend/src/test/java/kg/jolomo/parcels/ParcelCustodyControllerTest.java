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

/** SP08-02: HTTP-слой custody. */
class ParcelCustodyControllerTest {

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
    void custodyRoutesRequireASession() throws Exception {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));

        mvc.perform(post("/api/v1/parcels/" + parcelId + "/pickup")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/parcels/" + parcelId + "/dispatch")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/parcels/" + parcelId + "/arrive")).andExpect(status().isUnauthorized());
        verify(parcels, never()).confirmPickup(any(), any());
    }

    @Test
    void custodyRoutesCallTheServiceAsTheSignedInUser() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(parcels.confirmPickup(user, parcelId)).thenReturn(parcel(ParcelStatus.PICKUP_CONFIRMED));
        when(parcels.dispatch(user, parcelId)).thenReturn(parcel(ParcelStatus.IN_TRANSIT));
        when(parcels.arrive(user, parcelId)).thenReturn(parcel(ParcelStatus.DELIVERY_PENDING));

        mvc.perform(post("/api/v1/parcels/" + parcelId + "/pickup"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKUP_CONFIRMED"));
        mvc.perform(post("/api/v1/parcels/" + parcelId + "/dispatch"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_TRANSIT"));
        mvc.perform(post("/api/v1/parcels/" + parcelId + "/arrive"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERY_PENDING"));
    }

    @Test
    void refusalsBecomeTheErrorEnvelope() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(parcels.dispatch(user, parcelId))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "INVALID_PARCEL_STATE", "error.parcel.invalid_state"));

        mvc.perform(post("/api/v1/parcels/" + parcelId + "/dispatch"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PARCEL_STATE"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
