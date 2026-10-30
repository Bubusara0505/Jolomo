package kg.jolomo.bookings;

import static kg.jolomo.bookings.BookingFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore.StoredEvent;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP07-03: COMPLETED и NO_SHOW ставит только водитель после отправления; по часам бронь не завершается. */
class BookingCompletionTest extends PostgisTestBase {

    private BookingFixture f;
    private UUID driver;
    private UUID passenger;
    private UUID trip;
    private BookingService.Booking accepted;

    @BeforeEach
    void setUp() {
        f = new BookingFixture(jdbc, tx);
        driver = f.driver();
        passenger = f.user();
        trip = f.publishedTrip(driver, 4);
        accepted = f.bookings.accept(driver, f.bookings.request(passenger, trip, 2).id());
    }

    private void departed() {
        f.clock.advance(Duration.ofDays(1).plusMinutes(1));
    }

    @Test
    void driverCompletesAfterDepartureAndSeatsStayTaken() {
        departed();

        BookingService.Booking done = f.bookings.complete(driver, accepted.id());

        assertThat(done.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(f.seatsAvailable(trip)).isEqualTo(2);
        assertThat(f.events.forAggregate("BOOKING", accepted.id())).extracting(StoredEvent::eventType)
                .containsExactly("BOOKING_REQUESTED", "BOOKING_ACCEPTED", "BOOKING_COMPLETED");
        StoredEvent last = f.events.forAggregate("BOOKING", accepted.id()).get(2);
        assertThat(last.actorUserId()).isEqualTo(driver);
        assertThat(jdbc.sql("SELECT payload->>'from' || '>' || (payload->>'to') FROM domain_events WHERE id = :id")
                .param("id", last.id()).query(String.class).single()).isEqualTo("ACCEPTED>COMPLETED");
    }

    @Test
    void noShowIsASeparateStateWithItsOwnEvent() {
        departed();

        BookingService.Booking missed = f.bookings.markNoShow(driver, accepted.id());

        assertThat(missed.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(f.seatsAvailable(trip)).isEqualTo(2);
        assertThat(f.events.forAggregate("BOOKING", accepted.id())).extracting(StoredEvent::eventType)
                .containsExactly("BOOKING_REQUESTED", "BOOKING_ACCEPTED", "BOOKING_NO_SHOW");
    }

    @Test
    void notBeforeDepartureButAllowedExactlyAtDeparture() {
        expectCode(() -> f.bookings.complete(driver, accepted.id()), "TRIP_NOT_DEPARTED");
        expectCode(() -> f.bookings.markNoShow(driver, accepted.id()), "TRIP_NOT_DEPARTED");
        f.clock.advance(Duration.ofDays(1).minusSeconds(1));
        expectCode(() -> f.bookings.complete(driver, accepted.id()), "TRIP_NOT_DEPARTED");
        assertThat(f.bookings.find(accepted.id()).orElseThrow().status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(f.events.forAggregate("BOOKING", accepted.id())).hasSize(2);

        f.clock.advance(Duration.ofSeconds(1));
        assertThat(f.bookings.complete(driver, accepted.id()).status()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void timePassingNeverCompletesABookingByItself() {
        f.clock.advance(Duration.ofDays(30));

        assertThat(f.bookings.find(accepted.id()).orElseThrow().status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(f.bookings.listMine(passenger).get(0).status()).isEqualTo(BookingStatus.ACCEPTED);
    }

    @Test
    void onlyTheTripDriverCanFinishTheBooking() {
        departed();
        UUID otherDriver = f.driver();
        UUID stranger = f.user();

        expectCode(() -> f.bookings.complete(passenger, accepted.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> f.bookings.markNoShow(passenger, accepted.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> f.bookings.complete(otherDriver, accepted.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> f.bookings.complete(stranger, accepted.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> f.bookings.complete(driver, UUID.randomUUID()), "BOOKING_NOT_FOUND");
        assertThat(f.bookings.find(accepted.id()).orElseThrow().status()).isEqualTo(BookingStatus.ACCEPTED);
    }

    @Test
    void repeatIsSafeAndTheTwoOutcomesExcludeEachOther() {
        BookingService.Booking other = f.bookings.request(f.user(), trip, 1);
        departed();
        f.bookings.complete(driver, accepted.id());
        long events = jdbc.sql("SELECT count(*) FROM domain_events").query(Long.class).single();

        assertThat(f.bookings.complete(driver, accepted.id()).status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(jdbc.sql("SELECT count(*) FROM domain_events").query(Long.class).single()).isEqualTo(events);
        expectCode(() -> f.bookings.markNoShow(driver, accepted.id()), "INVALID_BOOKING_STATE");
        // исход не переписать задним числом: то же в обратную сторону
        expectCode(() -> f.bookings.complete(driver, other.id()), "INVALID_BOOKING_STATE");
    }

    @Test
    void suspendedDriverCanStillRecordWhatHappened() {
        departed();
        f.profiles.suspend(driver, UUID.randomUUID(), "SUSPICIOUS_ACTIVITY");

        assertThat(f.bookings.complete(driver, accepted.id()).status()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void contactsStayClosedAfterTheOutcome() {
        BookingContactService contacts = new BookingContactService(jdbc);
        assertThat(contacts.contactFor(driver, accepted.id()).phone()).isNotBlank();
        departed();

        f.bookings.complete(driver, accepted.id());

        expectCode(() -> contacts.contactFor(driver, accepted.id()), "CONTACT_NOT_AVAILABLE");
        expectCode(() -> contacts.contactFor(passenger, accepted.id()), "CONTACT_NOT_AVAILABLE");
    }

    @Test
    void passengerCannotCancelAfterDepartureAndNothingIsReleased() {
        departed();

        expectCode(() -> f.bookings.cancel(passenger, accepted.id()), "TRIP_DEPARTED");
        assertThat(f.seatsAvailable(trip)).isEqualTo(2);
    }
}
