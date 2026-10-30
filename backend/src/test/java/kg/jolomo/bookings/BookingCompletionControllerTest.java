package kg.jolomo.bookings;

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

/** SP07-03: HTTP-слой завершения и неявки. */
class BookingCompletionControllerTest {

    private final SessionAuth auth = mock(SessionAuth.class);
    private final BookingService bookings = mock(BookingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BookingController(auth, bookings))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private final UUID user = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-10-27T09:00:00Z");

    private BookingService.Booking booking(BookingStatus s) {
        return new BookingService.Booking(bookingId, UUID.randomUUID(), UUID.randomUUID(), 1, s, 700, 700, "KGS", at, at);
    }

    @Test
    void anonymousCannotFinishABooking() throws Exception {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));

        mvc.perform(post("/api/v1/bookings/" + bookingId + "/complete")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/no-show")).andExpect(status().isUnauthorized());
        verify(bookings, never()).complete(any(), any());
        verify(bookings, never()).markNoShow(any(), any());
    }

    @Test
    void completeAndNoShowReturnTheBookingInItsNewState() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(bookings.complete(user, bookingId)).thenReturn(booking(BookingStatus.COMPLETED));
        when(bookings.markNoShow(user, bookingId)).thenReturn(booking(BookingStatus.NO_SHOW));

        mvc.perform(post("/api/v1/bookings/" + bookingId + "/complete"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/v1/bookings/" + bookingId + "/no-show"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NO_SHOW"));
    }

    @Test
    void serviceRefusalsUseTheErrorEnvelope() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(bookings.complete(user, bookingId))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "TRIP_NOT_DEPARTED", "error.booking.trip_not_departed"));

        mvc.perform(post("/api/v1/bookings/" + bookingId + "/complete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_DEPARTED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
