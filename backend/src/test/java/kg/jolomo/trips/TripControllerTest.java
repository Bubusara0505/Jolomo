package kg.jolomo.trips;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP05-01: HTTP-слой поездок. Сервисы подменены, проверяем авторизацию и разбор запросов. */
class TripControllerTest {

    private final SessionAuth auth = mock(SessionAuth.class);
    private final TripService trips = mock(TripService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TripController(auth, trips))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private final UUID user = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID pickup = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-11-01T06:00:00Z");

    private TripService.Trip trip(TripStatus s, UUID owner) {
        return new TripService.Trip(tripId, owner, s, Direction.BISHKEK_TO_KARAKOL, pickup, from, from.plusSeconds(7200),
                4, 4, 700, 2, 2);
    }

    private void signedIn() {
        when(auth.requireUserId(any())).thenReturn(user);
        when(auth.userIdIfPresent(any())).thenReturn(Optional.of(user));
    }

    private void anonymous() {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));
        when(auth.userIdIfPresent(any())).thenReturn(Optional.empty());
    }

    @Test
    void mutationsAndOwnListRequireASession() throws Exception {
        anonymous();

        mvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON).content("""
                        {"direction":"BISHKEK_TO_KARAKOL","pickupLocationId":"%s","departureFrom":"2026-11-01T06:00:00Z",
                         "departureTo":"2026-11-01T08:00:00Z","seatsTotal":4,"priceKgs":700}
                        """.formatted(pickup)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/trips/mine")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/trips/" + tripId).contentType(MediaType.APPLICATION_JSON).content("{\"priceKgs\":1}"))
                .andExpect(status().isUnauthorized());
        verify(trips, never()).createDraft(any(), any());
    }

    @Test
    void createReturns201WithOwnerView() throws Exception {
        signedIn();
        when(trips.createDraft(eq(user), any())).thenReturn(trip(TripStatus.DRAFT, user));

        mvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON).content("""
                        {"direction":"BISHKEK_TO_KARAKOL","pickupLocationId":"%s","departureFrom":"2026-11-01T06:00:00Z",
                         "departureTo":"2026-11-01T08:00:00Z","seatsTotal":4,"priceKgs":700,"parcelSlotsTotal":2}
                        """.formatted(pickup)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currency").value("KGS"))
                .andExpect(jsonPath("$.seatsAvailable").value(4));
    }

    @Test
    void unknownDirectionIsAValidationErrorNotAServerError() throws Exception {
        signedIn();

        mvc.perform(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"MOON\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void ownerSeesOwnTripInAnyStatus() throws Exception {
        signedIn();
        when(trips.find(tripId)).thenReturn(Optional.of(trip(TripStatus.DRAFT, user)));

        mvc.perform(get("/api/v1/trips/" + tripId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.parcelSlotsTotal").value(2));
    }

    @Test
    void strangersAndAnonymousGetThePublicViewOrNotFound() throws Exception {
        anonymous();
        UUID owner = UUID.randomUUID();
        when(trips.find(tripId)).thenReturn(Optional.of(trip(TripStatus.DRAFT, owner)));
        when(trips.findPublic(tripId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/trips/" + tripId)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));

        when(trips.findPublic(tripId)).thenReturn(Optional.of(new TripService.PublicTrip(tripId, Direction.BISHKEK_TO_KARAKOL,
                from, from.plusSeconds(7200), "BISHKEK_WEST_BUS_STATION", "ky", "ru", "en", 42.8, 74.5, 3, 700, "KGS")));
        mvc.perform(get("/api/v1/trips/" + tripId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceKgs").value(700))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.driverUserId").doesNotExist());
    }

    @Test
    void patchWithStatusPublishesOrCancelsButNeverMixedWithFields() throws Exception {
        signedIn();
        when(trips.publish(user, tripId)).thenReturn(trip(TripStatus.PUBLISHED, user));
        when(trips.cancel(user, tripId)).thenReturn(trip(TripStatus.CANCELLED, user));

        mvc.perform(patch("/api/v1/trips/" + tripId).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(patch("/api/v1/trips/" + tripId).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(patch("/api/v1/trips/" + tripId).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\",\"priceKgs\":5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(patch("/api/v1/trips/" + tripId).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
    }

    @Test
    void patchWithoutStatusEditsFields() throws Exception {
        signedIn();
        when(trips.update(eq(user), eq(tripId), any())).thenReturn(trip(TripStatus.DRAFT, user));

        mvc.perform(patch("/api/v1/trips/" + tripId).contentType(MediaType.APPLICATION_JSON).content("{\"priceKgs\":800}"))
                .andExpect(status().isOk());
        verify(trips).update(eq(user), eq(tripId), any());
    }

    @Test
    void mineListsOwnTrips() throws Exception {
        signedIn();
        when(trips.listOwn(user)).thenReturn(List.of(trip(TripStatus.DRAFT, user)));

        mvc.perform(get("/api/v1/trips/mine")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(tripId.toString()));
    }
}
