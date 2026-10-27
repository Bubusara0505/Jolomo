package kg.jolomo.bookings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kg.jolomo.shared.ApiException;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T-01: гонки на настоящем PostGIS. У последнего места один победитель, места не списываются и не возвращаются дважды. */
class BookingRaceTest extends PostgisTestBase {

    private static final int THREADS = 8;

    private BookingFixture f;
    private UUID driver;

    @BeforeEach
    void setUp() {
        f = new BookingFixture(jdbc, tx);
        driver = f.driver();
    }

    /** Запускает все действия одновременно и возвращает коды исходов: OK или код ApiException. */
    private List<String> raceAll(List<java.util.concurrent.Callable<Object>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (var action : actions) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        action.call();
                        return "OK";
                    } catch (ApiException e) {
                        return e.code();
                    }
                }));
            }
            start.countDown();
            List<String> codes = new ArrayList<>();
            for (Future<String> fut : futures) {
                codes.add(fut.get());
            }
            return codes;
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void lastSeatHasExactlyOneWinnerWhenManyRequestsAreAcceptedAtOnce() throws Exception {
        UUID trip = f.publishedTrip(driver, 1);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            ids.add(f.bookings.request(f.user(), trip, 1).id());
        }
        List<java.util.concurrent.Callable<Object>> actions = new ArrayList<>();
        for (UUID id : ids) {
            actions.add(() -> f.bookings.accept(driver, id));
        }

        List<String> codes = raceAll(actions);

        assertThat(codes.stream().filter("OK"::equals)).hasSize(1);
        assertThat(codes.stream().filter("NO_SEATS_LEFT"::equals)).hasSize(THREADS - 1);
        assertThat(f.seatsAvailable(trip)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM bookings WHERE status = 'ACCEPTED'").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void severalSeatsAreNeverOversoldUnderContention() throws Exception {
        UUID trip = f.publishedTrip(driver, 3);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            ids.add(f.bookings.request(f.user(), trip, 1).id());
        }
        List<java.util.concurrent.Callable<Object>> actions = new ArrayList<>();
        for (UUID id : ids) {
            actions.add(() -> f.bookings.accept(driver, id));
        }

        List<String> codes = raceAll(actions);

        assertThat(codes.stream().filter("OK"::equals)).hasSize(3);
        assertThat(f.seatsAvailable(trip)).isZero();
    }

    @Test
    void acceptingTheSameBookingConcurrentlyTakesSeatsOnce() throws Exception {
        UUID trip = f.publishedTrip(driver, 4);
        UUID id = f.bookings.request(f.user(), trip, 2).id();
        List<java.util.concurrent.Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(() -> f.bookings.accept(driver, id));
        }

        List<String> codes = raceAll(actions);

        assertThat(codes).containsOnly("OK");
        assertThat(f.seatsAvailable(trip)).isEqualTo(2);
    }

    @Test
    void cancellingTheSameAcceptedBookingConcurrentlyFreesSeatsOnce() throws Exception {
        UUID trip = f.publishedTrip(driver, 4);
        BookingService.Booking b = f.bookings.request(f.user(), trip, 2);
        f.bookings.accept(driver, b.id());
        List<java.util.concurrent.Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(() -> f.bookings.cancel(b.passengerId(), b.id()));
        }

        List<String> codes = raceAll(actions);

        assertThat(codes).containsOnly("OK");
        assertThat(f.seatsAvailable(trip)).isEqualTo(4);
    }

    @Test
    void acceptAndCancelRacingOnTheSameBookingLeaveConsistentSeats() throws Exception {
        UUID trip = f.publishedTrip(driver, 4);
        BookingService.Booking b = f.bookings.request(f.user(), trip, 2);
        List<java.util.concurrent.Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            actions.add(() -> f.bookings.accept(driver, b.id()));
            actions.add(() -> f.bookings.cancel(b.passengerId(), b.id()));
        }

        raceAll(actions);

        BookingStatus status = f.bookings.find(b.id()).orElseThrow().status();
        // Итог один из двух, и места ему соответствуют: ни утечки, ни двойного списания.
        assertThat(status).isIn(BookingStatus.ACCEPTED, BookingStatus.CANCELLED);
        assertThat(f.seatsAvailable(trip)).isEqualTo(status == BookingStatus.ACCEPTED ? 2 : 4);
    }

    @Test
    void parallelRequestsFromOnePassengerCreateOneActiveBooking() throws Exception {
        UUID trip = f.publishedTrip(driver, 4);
        UUID passenger = f.user();
        List<java.util.concurrent.Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(() -> f.bookings.request(passenger, trip, 1));
        }

        List<String> codes = raceAll(actions);

        assertThat(codes.stream().filter("OK"::equals)).hasSize(1);
        assertThat(codes.stream().filter("BOOKING_ALREADY_ACTIVE"::equals)).hasSize(THREADS - 1);
        assertThat(f.bookingCount()).isEqualTo(1L);
    }

    @Test
    void tripCancellationRacingWithRequestsLeavesNoActiveBookingOnTheCancelledTrip() throws Exception {
        UUID trip = f.publishedTrip(driver, 8);
        List<java.util.concurrent.Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID passenger = f.user();
            actions.add(() -> f.bookings.request(passenger, trip, 1));
        }
        actions.add(() -> f.trips.cancel(driver, trip));

        raceAll(actions);

        assertThat(jdbc.sql("SELECT count(*) FROM bookings WHERE trip_id = :t AND status IN ('REQUESTED', 'ACCEPTED')")
                        .param("t", trip)
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
