package kg.jolomo.bookings;

import static kg.jolomo.bookings.BookingFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore.StoredEvent;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP06-02: принятие, отказ и отмена. Матрица «кто и из какого состояния что может» и учёт мест. */
class BookingDecisionTest extends PostgisTestBase {

    private BookingFixture f;
    private UUID driver;
    private UUID passenger;
    private UUID trip;

    @BeforeEach
    void setUp() {
        f = new BookingFixture(jdbc, tx);
        driver = f.driver();
        passenger = f.user();
        trip = f.publishedTrip(driver, 4);
    }

    private BookingService.Booking requested(int seats) {
        return f.bookings.request(f.user(), trip, seats);
    }

    @Test
    void acceptTakesTheSeatsAndRepeatedAcceptTakesThemOnlyOnce() {
        BookingService.Booking b = requested(2);

        BookingService.Booking accepted = f.bookings.accept(driver, b.id());
        BookingService.Booking again = f.bookings.accept(driver, b.id());

        assertThat(accepted.status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(again.status()).isEqualTo(BookingStatus.ACCEPTED);
        assertThat(f.seatsAvailable(trip)).isEqualTo(2);
        assertThat(f.events.forAggregate("BOOKING", b.id())).extracting(StoredEvent::eventType)
                .containsExactly("BOOKING_REQUESTED", "BOOKING_ACCEPTED");
    }

    @Test
    void acceptIsRefusedWhenNotEnoughSeatsRemain() {
        BookingService.Booking big = requested(3);
        BookingService.Booking other = requested(2);
        f.bookings.accept(driver, big.id());

        expectCode(() -> f.bookings.accept(driver, other.id()), "NO_SEATS_LEFT");

        assertThat(f.bookings.find(other.id()).orElseThrow().status()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(f.seatsAvailable(trip)).isEqualTo(1);
    }

    @Test
    void rejectChangesNoSeatsAndRepeatedRejectIsSafe() {
        BookingService.Booking b = requested(2);

        assertThat(f.bookings.reject(driver, b.id()).status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(f.bookings.reject(driver, b.id()).status()).isEqualTo(BookingStatus.REJECTED);

        assertThat(f.seatsAvailable(trip)).isEqualTo(4);
        assertThat(f.events.forAggregate("BOOKING", b.id())).extracting(StoredEvent::eventType)
                .containsExactly("BOOKING_REQUESTED", "BOOKING_REJECTED");
    }

    @Test
    void cancellingAnAcceptedBookingFreesTheSeatsExactlyOnce() {
        BookingService.Booking b = requested(2);
        f.bookings.accept(driver, b.id());
        UUID owner = b.passengerId();

        assertThat(f.bookings.cancel(owner, b.id()).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(f.bookings.cancel(owner, b.id()).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(f.bookings.cancel(owner, b.id()).status()).isEqualTo(BookingStatus.CANCELLED);

        assertThat(f.seatsAvailable(trip)).isEqualTo(4);
        assertThat(f.events.forAggregate("BOOKING", b.id())).extracting(StoredEvent::eventType)
                .containsExactly("BOOKING_REQUESTED", "BOOKING_ACCEPTED", "BOOKING_CANCELLED");
    }

    @Test
    void cancellingARequestedBookingFreesNothing() {
        BookingService.Booking b = requested(2);

        f.bookings.cancel(b.passengerId(), b.id());

        assertThat(f.seatsAvailable(trip)).isEqualTo(4);
    }

    @Test
    void driverCanCancelAnAcceptedBookingButNotAnUnacceptedRequest() {
        BookingService.Booking accepted = requested(1);
        BookingService.Booking pending = requested(1);
        f.bookings.accept(driver, accepted.id());

        assertThat(f.bookings.cancel(driver, accepted.id()).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(f.seatsAvailable(trip)).isEqualTo(4);
        expectCode(() -> f.bookings.cancel(driver, pending.id()), "INVALID_BOOKING_STATE");
    }

    @Test
    void forbiddenTransitionsAreRefused() {
        BookingService.Booking rejected = requested(1);
        f.bookings.reject(driver, rejected.id());
        BookingService.Booking accepted = requested(1);
        f.bookings.accept(driver, accepted.id());
        BookingService.Booking cancelled = requested(1);
        f.bookings.cancel(cancelled.passengerId(), cancelled.id());

        expectCode(() -> f.bookings.accept(driver, rejected.id()), "INVALID_BOOKING_STATE");
        expectCode(() -> f.bookings.reject(driver, accepted.id()), "INVALID_BOOKING_STATE");
        expectCode(() -> f.bookings.accept(driver, cancelled.id()), "INVALID_BOOKING_STATE");
        expectCode(() -> f.bookings.reject(driver, cancelled.id()), "INVALID_BOOKING_STATE");
        expectCode(() -> f.bookings.cancel(rejected.passengerId(), rejected.id()), "INVALID_BOOKING_STATE");
    }

    @Test
    void onlyTheTripOwnerDecidesAndStrangersGetNotFound() {
        BookingService.Booking b = requested(1);
        UUID otherDriver = f.driver();
        UUID stranger = f.user();

        expectCode(() -> f.bookings.accept(otherDriver, b.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> f.bookings.reject(otherDriver, b.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> f.bookings.accept(b.passengerId(), b.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> f.bookings.cancel(stranger, b.id()), "BOOKING_NOT_FOUND");
        expectCode(() -> f.bookings.accept(driver, UUID.randomUUID()), "BOOKING_NOT_FOUND");
        assertThat(f.bookings.find(b.id()).orElseThrow().status()).isEqualTo(BookingStatus.REQUESTED);
    }

    @Test
    void anUnapprovedDriverCannotAcceptButCanStillRejectAndCancel() {
        BookingService.Booking toAccept = requested(1);
        BookingService.Booking toReject = requested(1);
        BookingService.Booking accepted = requested(1);
        f.bookings.accept(driver, accepted.id());
        jdbc.sql("UPDATE driver_profiles SET status = 'SUSPENDED' WHERE user_id = :u").param("u", driver).update();

        expectCode(() -> f.bookings.accept(driver, toAccept.id()), "NOT_APPROVED");
        assertThat(f.bookings.reject(driver, toReject.id()).status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(f.bookings.cancel(driver, accepted.id()).status()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void afterDepartureNothingCanBeAcceptedOrCancelled() {
        BookingService.Booking pending = requested(1);
        BookingService.Booking accepted = requested(1);
        f.bookings.accept(driver, accepted.id());
        f.clock.advance(Duration.ofDays(2));

        expectCode(() -> f.bookings.accept(driver, pending.id()), "TRIP_DEPARTED");
        expectCode(() -> f.bookings.cancel(accepted.passengerId(), accepted.id()), "TRIP_DEPARTED");
        assertThat(f.bookings.find(accepted.id()).orElseThrow().status()).isEqualTo(BookingStatus.ACCEPTED);
    }

    @Test
    void cancellingTheTripCancelsEveryActiveBookingAndNewRequestsAreRefused() {
        BookingService.Booking pending = requested(1);
        BookingService.Booking accepted = requested(1);
        BookingService.Booking rejected = requested(1);
        f.bookings.accept(driver, accepted.id());
        f.bookings.reject(driver, rejected.id());

        f.trips.cancel(driver, trip);

        assertThat(f.bookings.find(pending.id()).orElseThrow().status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(f.bookings.find(accepted.id()).orElseThrow().status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(f.bookings.find(rejected.id()).orElseThrow().status()).isEqualTo(BookingStatus.REJECTED);
        assertThat(f.events.forAggregate("BOOKING", accepted.id())).extracting(StoredEvent::eventType)
                .containsExactly("BOOKING_REQUESTED", "BOOKING_ACCEPTED", "BOOKING_CANCELLED");
        expectCode(() -> f.bookings.request(f.user(), trip, 1), "TRIP_NOT_FOUND");
        // Повторная отмена поездки безопасна и ничего не ломает.
        f.trips.cancel(driver, trip);
    }

    @Test
    void bookingsAreVisibleOnlyToThePassengerAndTheTripDriver() {
        BookingService.Booking b = requested(1);
        UUID stranger = f.user();

        assertThat(f.bookings.findVisible(b.passengerId(), b.id())).isPresent();
        assertThat(f.bookings.findVisible(driver, b.id())).isPresent();
        assertThat(f.bookings.findVisible(stranger, b.id())).isEmpty();
        assertThat(f.bookings.listMine(b.passengerId())).extracting(BookingService.Booking::id).containsExactly(b.id());
        assertThat(f.bookings.listMine(stranger)).isEmpty();
        assertThat(f.bookings.listForTrip(driver, trip)).extracting(BookingService.Booking::id).containsExactly(b.id());
        expectCode(() -> f.bookings.listForTrip(stranger, trip), "TRIP_NOT_FOUND");
    }

    @Test
    void databaseNeverLetsSeatsGoBelowZero() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> jdbc.sql("UPDATE trips SET seats_available = -1 WHERE id = :id").param("id", trip).update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
