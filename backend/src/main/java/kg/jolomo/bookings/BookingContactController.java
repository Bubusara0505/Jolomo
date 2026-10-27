package kg.jolomo.bookings;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Контакт другой стороны по принятой брони (SP07-01). Единственное место, где API отдаёт телефон по броне. */
@RestController
@RequestMapping("/api/v1/bookings")
class BookingContactController {

    private final SessionAuth auth;
    private final BookingContactService contacts;

    BookingContactController(SessionAuth auth, BookingContactService contacts) {
        this.auth = auth;
        this.contacts = contacts;
    }

    @GetMapping("/{id}/contact")
    ResponseEntity<BookingContactService.Contact> contact(HttpServletRequest request, @PathVariable UUID id) {
        UUID user = auth.requireUserId(request);
        // Телефон не должен оседать в кэшах браузера и прокси.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(contacts.contactFor(user, id));
    }
}
