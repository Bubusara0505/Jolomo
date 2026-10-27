package kg.jolomo.bookings;

import static kg.jolomo.bookings.BookingFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP07-01 (RQ-07): контакты только после ACCEPTED и только другой стороны. */
class BookingContactServiceTest extends PostgisTestBase {

    private BookingFixture f;
    private BookingContactService contacts;
    private UUID driver;
    private UUID passenger;
    private UUID trip;

    @BeforeEach
    void setUp() {
        f = new BookingFixture(jdbc, tx);
        contacts = new BookingContactService(jdbc);
        driver = f.driver();
        passenger = f.user();
        trip = f.publishedTrip(driver, 4);
    }

    private String phoneOf(UUID user) {
        return jdbc.sql("SELECT phone FROM users WHERE id = :id").param("id", user).query(String.class).single();
    }

    @Test
    void beforeAcceptanceNeitherSideGetsAContact() {
        BookingService.Booking b = f.bookings.request(passenger, trip, 1);

        expectCode(() -> contacts.contactFor(passenger, b.id()), "CONTACT_NOT_AVAILABLE");
        expectCode(() -> contacts.contactFor(driver, b.id()), "CONTACT_NOT_AVAILABLE");
    }

    @Test
    void afterAcceptanceEachSideGetsOnlyTheOtherSidesPhone() {
        BookingService.Booking b = f.bookings.request(passenger, trip, 1);
        f.bookings.accept(driver, b.id());

        BookingContactService.Contact forPassenger = contacts.contactFor(passenger, b.id());
        BookingContactService.Contact forDriver = contacts.contactFor(driver, b.id());

        assertThat(forPassenger.role()).isEqualTo("DRIVER");
        assertThat(forPassenger.phone()).isEqualTo(phoneOf(driver)).isNotEqualTo(phoneOf(passenger));
        assertThat(forDriver.role()).isEqualTo("PASSENGER");
        assertThat(forDriver.phone()).isEqualTo(phoneOf(passenger)).isNotEqualTo(phoneOf(driver));
        assertThat(forPassenger.bookingId()).isEqualTo(b.id());
    }

    @Test
    void strangersAndUnknownBookingsAreNotFoundEvenAfterAcceptance() {
        BookingService.Booking b = f.bookings.request(passenger, trip, 1);
        f.bookings.accept(driver, b.id());
        UUID stranger = f.user();
        UUID otherDriver = f.driver();

        expectCode(() -> contacts.contactFor(stranger, b.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> contacts.contactFor(otherDriver, b.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> contacts.contactFor(passenger, UUID.randomUUID()), "BOOKING_NOT_FOUND");
    }

    @Test
    void rejectedAndCancelledBookingsCloseTheContact() {
        BookingService.Booking rejected = f.bookings.request(passenger, trip, 1);
        f.bookings.reject(driver, rejected.id());
        UUID other = f.user();
        BookingService.Booking accepted = f.bookings.request(other, trip, 1);
        f.bookings.accept(driver, accepted.id());
        assertThat(contacts.contactFor(other, accepted.id()).phone()).isEqualTo(phoneOf(driver));

        f.bookings.cancel(other, accepted.id());

        expectCode(() -> contacts.contactFor(passenger, rejected.id()), "CONTACT_NOT_AVAILABLE");
        expectCode(() -> contacts.contactFor(other, accepted.id()), "CONTACT_NOT_AVAILABLE");
        expectCode(() -> contacts.contactFor(driver, accepted.id()), "CONTACT_NOT_AVAILABLE");
    }

    @Test
    void cancellingTheTripClosesTheContactOfAnAcceptedBooking() {
        BookingService.Booking b = f.bookings.request(passenger, trip, 1);
        f.bookings.accept(driver, b.id());

        f.trips.cancel(driver, trip);

        expectCode(() -> contacts.contactFor(passenger, b.id()), "CONTACT_NOT_AVAILABLE");
    }

    @Test
    void ordinaryBookingResponsesAndEventsNeverCarryPhonesEvenWhenAccepted() {
        BookingService.Booking requested = f.bookings.request(passenger, trip, 1);
        BookingService.Booking accepted = f.bookings.accept(driver, requested.id());

        String json = BookingService.toJson(accepted);
        String events = f.events.forAggregate("BOOKING", accepted.id()).toString();

        for (String phone : new String[] {phoneOf(driver), phoneOf(passenger)}) {
            assertThat(json).doesNotContain(phone);
            assertThat(events).doesNotContain(phone);
        }
        assertThat(json).doesNotContain("phone");
    }
}
