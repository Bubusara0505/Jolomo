package kg.jolomo.bookings;

import static kg.jolomo.bookings.BookingFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore.StoredEvent;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP06-01: запрос места. Нельзя запросить несуществующую, полную, просроченную или свою поездку и неверное число мест. */
class BookingRequestTest extends PostgisTestBase {

    private BookingFixture f;
    private UUID driver;
    private UUID passenger;

    @BeforeEach
    void setUp() {
        f = new BookingFixture(jdbc, tx);
        driver = f.driver();
        passenger = f.user();
    }

    @Test
    void validRequestIsStoredAsRequestedWithPriceAndTakesNoSeats() {
        UUID trip = f.publishedTrip(driver, 4);

        BookingService.Booking b = f.bookings.request(passenger, trip, 2);

        assertThat(b.status()).isEqualTo(BookingStatus.REQUESTED);
        assertThat(b.seats()).isEqualTo(2);
        assertThat(b.priceKgs()).isEqualTo(700);
        assertThat(b.totalKgs()).isEqualTo(1400);
        assertThat(b.currency()).isEqualTo("KGS");
        assertThat(b.passengerId()).isEqualTo(passenger);
        // Места занимает только принятие водителем.
        assertThat(f.seatsAvailable(trip)).isEqualTo(4);
    }

    @Test
    void requestWritesADomainEventWithoutPhones() {
        UUID trip = f.publishedTrip(driver, 4);

        BookingService.Booking b = f.bookings.request(passenger, trip, 1);

        var events = f.events.forAggregate("BOOKING", b.id());
        assertThat(events).extracting(StoredEvent::eventType).containsExactly("BOOKING_REQUESTED");
        assertThat(events.get(0).payloadJson()).doesNotContain("+996").doesNotContain("phone");
    }

    @Test
    void theBookingViewHasNoContactFieldsAndTheStoredJsonHasNoPhone() {
        assertThat(Arrays.stream(BookingService.Booking.class.getRecordComponents()).map(RecordComponent::getName))
                .noneMatch(n -> n.toLowerCase().contains("phone") || n.toLowerCase().contains("contact"));
        UUID trip = f.publishedTrip(driver, 4);

        String json = BookingService.toJson(f.bookings.request(passenger, trip, 1));

        assertThat(json).doesNotContain("phone").doesNotContain("+996");
    }

    @Test
    void unknownTripDraftAndCancelledTripAreNotFound() {
        expectCode(() -> f.bookings.request(passenger, UUID.randomUUID(), 1), "TRIP_NOT_FOUND");

        java.time.Instant from = f.clock.instant().plus(Duration.ofDays(1));
        UUID draftId = f.trips.createDraft(driver, new kg.jolomo.trips.TripService.TripDraft(
                kg.jolomo.trips.Direction.BISHKEK_TO_KARAKOL, f.bishkekPoint, from, from.plus(Duration.ofHours(2)), 3, 700, 0)).id();
        expectCode(() -> f.bookings.request(passenger, draftId, 1), "TRIP_NOT_FOUND");

        UUID trip = f.publishedTrip(driver, 3);
        f.trips.cancel(driver, trip);
        expectCode(() -> f.bookings.request(passenger, trip, 1), "TRIP_NOT_FOUND");
    }

    @Test
    void invalidSeatCountsAreRejected() {
        UUID trip = f.publishedTrip(driver, 2);

        expectCode(() -> f.bookings.request(passenger, trip, 0), "INVALID_SEATS");
        expectCode(() -> f.bookings.request(passenger, trip, -1), "INVALID_SEATS");
        expectCode(() -> f.bookings.request(passenger, trip, 3), "NOT_ENOUGH_SEATS");
        assertThat(f.bookingCount()).isZero();
    }

    @Test
    void fullTripCannotBeRequested() {
        UUID trip = f.publishedTrip(driver, 1);
        BookingService.Booking first = f.bookings.request(passenger, trip, 1);
        f.bookings.accept(driver, first.id());

        expectCode(() -> f.bookings.request(f.user(), trip, 1), "NOT_ENOUGH_SEATS");
    }

    @Test
    void departedTripCannotBeRequested() {
        UUID trip = f.publishedTrip(driver, 2);
        f.clock.advance(Duration.ofDays(2));

        expectCode(() -> f.bookings.request(passenger, trip, 1), "TRIP_DEPARTED");
    }

    @Test
    void driverCannotBookHisOwnTrip() {
        UUID trip = f.publishedTrip(driver, 2);

        expectCode(() -> f.bookings.request(driver, trip, 1), "OWN_TRIP");
    }

    @Test
    void suspendedPassengerCannotRequest() {
        UUID trip = f.publishedTrip(driver, 2);
        jdbc.sql("UPDATE users SET status = 'SUSPENDED' WHERE id = :id").param("id", passenger).update();

        expectCode(() -> f.bookings.request(passenger, trip, 1), "USER_NOT_ACTIVE");
    }

    @Test
    void onlyOneActiveRequestPerPassengerAndTripButRetryAfterRejectionWorks() {
        UUID trip = f.publishedTrip(driver, 4);
        BookingService.Booking first = f.bookings.request(passenger, trip, 1);

        expectCode(() -> f.bookings.request(passenger, trip, 1), "BOOKING_ALREADY_ACTIVE");

        f.bookings.reject(driver, first.id());
        BookingService.Booking second = f.bookings.request(passenger, trip, 2);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(BookingStatus.REQUESTED);
    }

    @Test
    void databaseRefusesASecondActiveBookingEvenIfTheCodeIsBypassed() {
        UUID trip = f.publishedTrip(driver, 4);
        f.bookings.request(passenger, trip, 1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO bookings (id, trip_id, passenger_user_id, seats, price_kgs, status, created_at, updated_at)
                                VALUES (gen_random_uuid(), :t, :p, 1, 700, 'REQUESTED', now(), now())
                                """)
                        .param("t", trip)
                        .param("p", passenger)
                        .update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void priceChangeOfThePublishedTripDoesNotChangeAnExistingRequest() {
        UUID trip = f.publishedTrip(driver, 4);
        BookingService.Booking b = f.bookings.request(passenger, trip, 1);

        f.trips.update(driver, trip, new kg.jolomo.trips.TripService.TripPatch(null, null, null, null, null, 900, null));

        assertThat(f.bookings.find(b.id()).orElseThrow().priceKgs()).isEqualTo(700);
    }
}
