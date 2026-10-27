package kg.jolomo.bookings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP07-01: HTTP-слой контактов. Нужен вход; отказ сервиса приходит конвертом; телефон не кэшируется. */
class BookingContactControllerTest {

    private final SessionAuth auth = mock(SessionAuth.class);
    private final BookingContactService contacts = mock(BookingContactService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BookingContactController(auth, contacts))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private final UUID user = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @Test
    void anonymousCallerIsRefusedAndTheServiceIsNotAsked() throws Exception {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));

        mvc.perform(get("/api/v1/bookings/" + bookingId + "/contact")).andExpect(status().isUnauthorized());

        verify(contacts, never()).contactFor(any(), any());
    }

    @Test
    void acceptedBookingReturnsTheContactWithoutCaching() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(contacts.contactFor(user, bookingId))
                .thenReturn(new BookingContactService.Contact(bookingId, "DRIVER", "+996555000001"));

        mvc.perform(get("/api/v1/bookings/" + bookingId + "/contact"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.role").value("DRIVER"))
                .andExpect(jsonPath("$.phone").value("+996555000001"));
    }

    @Test
    void notYetAcceptedBookingIsAnErrorEnvelopeWithNoPhone() throws Exception {
        when(auth.requireUserId(any())).thenReturn(user);
        when(contacts.contactFor(user, bookingId))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "CONTACT_NOT_AVAILABLE", "error.booking.contact_not_available"));

        mvc.perform(get("/api/v1/bookings/" + bookingId + "/contact"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTACT_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
