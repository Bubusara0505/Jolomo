package kg.jolomo.bookings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

/** SP06: HTTP-слой броней. Сервис подменён, проверяем вход, заголовок ключа и разбор запросов. */
class BookingControllerTest {

    private final SessionAuth auth = mock(SessionAuth.class);
    private final BookingService bookings = mock(BookingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BookingController(auth, bookings))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private final UUID user = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-27T09:00:00Z");

    private BookingService.Booking booking(BookingStatus s) {
        return new BookingService.Booking(bookingId, tripId, user, 2, s, 700, 1400, "KGS", at, at);
    }

    private void signedIn() {
        when(auth.requireUserId(any())).thenReturn(user);
    }

    private void anonymous() {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));
    }

    @Test
    void everyRouteRequiresASession() throws Exception {
        anonymous();

        mvc.perform(post("/api/v1/trips/" + tripId + "/bookings").header("Idempotency-Key", "key-0000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/bookings/mine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/bookings/" + bookingId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/trips/" + tripId + "/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/accept")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/reject")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel")).andExpect(status().isUnauthorized());
        verify(bookings, never()).requestWithKey(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void requestWithoutAnIdempotencyKeyIsRejected() throws Exception {
        signedIn();

        mvc.perform(post("/api/v1/trips/" + tripId + "/bookings").contentType(MediaType.APPLICATION_JSON).content("{\"seats\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mvc.perform(post("/api/v1/trips/" + tripId + "/bookings").header("Idempotency-Key", "bad key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
    }

    @Test
    void missingSeatsIsAValidationError() throws Exception {
        signedIn();

        mvc.perform(post("/api/v1/trips/" + tripId + "/bookings").header("Idempotency-Key", "key-0000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEATS"));
    }

    @Test
    void requestReturns201WithTheStoredBodyAndMarksReplays() throws Exception {
        signedIn();
        String body = BookingService.toJson(booking(BookingStatus.REQUESTED));
        when(bookings.requestWithKey(eq(user), eq(tripId), eq(2), eq("key-0000001")))
                .thenReturn(new IdempotencyService.Result(201, body, false), new IdempotencyService.Result(201, body, true));

        mvc.perform(post("/api/v1/trips/" + tripId + "/bookings").header("Idempotency-Key", "key-0000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":2}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.totalKgs").value(1400))
                .andExpect(jsonPath("$.phone").doesNotExist());
        mvc.perform(post("/api/v1/trips/" + tripId + "/bookings").header("Idempotency-Key", "key-0000001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":2}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().json(body));
    }

    @Test
    void decisionRoutesCallTheServiceAsTheSignedInUser() throws Exception {
        signedIn();
        when(bookings.accept(user, bookingId)).thenReturn(booking(BookingStatus.ACCEPTED));
        when(bookings.reject(user, bookingId)).thenReturn(booking(BookingStatus.REJECTED));
        when(bookings.cancel(user, bookingId)).thenReturn(booking(BookingStatus.CANCELLED));

        mvc.perform(post("/api/v1/bookings/" + bookingId + "/accept"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/reject"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void domainRefusalsBecomeTheErrorEnvelope() throws Exception {
        signedIn();
        when(bookings.accept(user, bookingId))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "NO_SEATS_LEFT", "error.booking.no_seats_left"));

        mvc.perform(post("/api/v1/bookings/" + bookingId + "/accept"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SEATS_LEFT"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void aBookingOfSomeoneElseIsNotFoundAndMineListsOnlyMine() throws Exception {
        signedIn();
        when(bookings.findVisible(user, bookingId)).thenReturn(Optional.empty());
        when(bookings.listMine(user)).thenReturn(List.of(booking(BookingStatus.REQUESTED)));

        mvc.perform(get("/api/v1/bookings/" + bookingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        mvc.perform(get("/api/v1/bookings/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(bookingId.toString()))
                .andExpect(jsonPath("$[0].phone").doesNotExist());
    }
}
