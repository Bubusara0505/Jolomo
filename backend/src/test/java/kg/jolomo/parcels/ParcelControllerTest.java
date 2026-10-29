package kg.jolomo.parcels;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import kg.jolomo.shared.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP07-04: HTTP-слой посылок. */
class ParcelControllerTest {

    private static final String BODY =
            "{\"tripId\":\"%s\",\"size\":\"S\",\"category\":\"DOCUMENTS\",\"recipientPhone\":\"+996700000001\"}";

    private final SessionAuth auth = mock(SessionAuth.class);
    private final ParcelService parcels = mock(ParcelService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ParcelController(auth, parcels))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private final UUID user = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID parcelId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-27T09:00:00Z");

    private ParcelService.Parcel parcel() {
        return new ParcelService.Parcel(parcelId, tripId, user, "S", "DOCUMENTS", ParcelStatus.REQUESTED, at, at);
    }

    @Test
    void everyRouteRequiresASession() throws Exception {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));

        mvc.perform(post("/api/v1/parcels").header("Idempotency-Key", "parcel-key-001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(tripId)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/parcels/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/parcels/" + parcelId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/trips/" + tripId + "/parcels")).andExpect(status().isUnauthorized());
        verify(parcels, never()).createWithKey(any(), any(), any());
    }

    @Test
    void creationWithoutAnIdempotencyKeyIsRejected() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);

        mvc.perform(post("/api/v1/parcels").contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(tripId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void creationReturns201AndMarksReplaysAndHasNoPhone() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        String stored = ParcelService.toJson(parcel());
        when(parcels.createWithKey(eq(user), any(), eq("parcel-key-001")))
                .thenReturn(new IdempotencyService.Result(201, stored, false), new IdempotencyService.Result(201, stored, true));

        mvc.perform(post("/api/v1/parcels").header("Idempotency-Key", "parcel-key-001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(tripId)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.recipientPhone").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
        mvc.perform(post("/api/v1/parcels").header("Idempotency-Key", "parcel-key-001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(tripId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void obligationRefusalBecomesTheErrorEnvelope() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(parcels.createWithKey(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "OBLIGATIONS_NOT_LOADED", "error.parcel.obligations_not_loaded"));

        mvc.perform(post("/api/v1/parcels").header("Idempotency-Key", "parcel-key-001")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(tripId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OBLIGATIONS_NOT_LOADED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void missingBodyIsAValidationError() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);

        mvc.perform(post("/api/v1/parcels").header("Idempotency-Key", "parcel-key-001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readRoutesReturnOnlyVisibleParcels() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(parcels.findVisible(user, parcelId)).thenReturn(Optional.empty());
        when(parcels.listMine(user)).thenReturn(List.of(parcel()));

        mvc.perform(get("/api/v1/parcels/" + parcelId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARCEL_NOT_FOUND"));
        mvc.perform(get("/api/v1/parcels/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(parcelId.toString()))
                .andExpect(jsonPath("$[0].recipientPhone").doesNotExist());
    }
}
