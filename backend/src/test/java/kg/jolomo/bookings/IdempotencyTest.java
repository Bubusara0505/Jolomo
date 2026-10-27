package kg.jolomo.bookings;

import static kg.jolomo.bookings.BookingFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kg.jolomo.shared.IdempotencyService;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP06-03 (RQ-06): Idempotency-Key для запроса места. Ответ хранится в базе и переживает рестарт. */
class IdempotencyTest extends PostgisTestBase {

    private static final String KEY = "key-0000001";

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

    @Test
    void sameKeyAndSameBodyReturnTheStoredResponseAndCreateOneBooking() {
        IdempotencyService.Result first = f.bookings.requestWithKey(passenger, trip, 2, KEY);
        IdempotencyService.Result second = f.bookings.requestWithKey(passenger, trip, 2, KEY);

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(f.bookingCount()).isEqualTo(1L);
    }

    @Test
    void theStoredResponseSurvivesARestartOfTheServices() {
        IdempotencyService.Result first = f.bookings.requestWithKey(passenger, trip, 2, KEY);

        BookingService afterRestart = f.restartedBookings();
        IdempotencyService.Result again = afterRestart.requestWithKey(passenger, trip, 2, KEY);

        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(f.bookingCount()).isEqualTo(1L);
    }

    @Test
    void sameKeyWithADifferentBodyIsAConflictAndChangesNothing() {
        IdempotencyService.Result first = f.bookings.requestWithKey(passenger, trip, 2, KEY);

        expectCode(() -> f.bookings.requestWithKey(passenger, trip, 3, KEY), "IDEMPOTENCY_KEY_REUSED");
        expectCode(() -> f.bookings.requestWithKey(passenger, f.publishedTrip(driver, 2), 2, KEY), "IDEMPOTENCY_KEY_REUSED");

        assertThat(f.bookingCount()).isEqualTo(1L);
        assertThat(f.bookings.requestWithKey(passenger, trip, 2, KEY).body()).isEqualTo(first.body());
    }

    @Test
    void replayStillReturnsTheStoredAnswerAfterTheTripBecameFull() {
        UUID small = f.publishedTrip(driver, 1);
        IdempotencyService.Result first = f.bookings.requestWithKey(passenger, small, 1, KEY);
        f.bookings.accept(driver, f.bookings.listMine(passenger).get(0).id());

        IdempotencyService.Result again = f.bookings.requestWithKey(passenger, small, 1, KEY);

        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(f.bookingCount()).isEqualTo(1L);
    }

    @Test
    void aFailedRequestIsNotStoredSoTheSameKeyWorksOnceTheCauseIsGone() {
        UUID small = f.publishedTrip(driver, 1);
        BookingService.Booking taker = f.bookings.request(f.user(), small, 1);
        f.bookings.accept(driver, taker.id());
        UUID second = f.user();

        expectCode(() -> f.bookings.requestWithKey(second, small, 1, KEY), "NOT_ENOUGH_SEATS");
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_records").query(Long.class).single()).isZero();

        f.bookings.cancel(taker.passengerId(), taker.id());
        IdempotencyService.Result retry = f.bookings.requestWithKey(second, small, 1, KEY);

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    void theSameKeyFromDifferentUsersIsIndependent() {
        UUID other = f.user();

        IdempotencyService.Result a = f.bookings.requestWithKey(passenger, trip, 1, KEY);
        IdempotencyService.Result b = f.bookings.requestWithKey(other, trip, 1, KEY);

        assertThat(a.replayed()).isFalse();
        assertThat(b.replayed()).isFalse();
        assertThat(a.body()).isNotEqualTo(b.body());
        assertThat(f.bookingCount()).isEqualTo(2L);
    }

    @Test
    void keyMustBePresentAndWellFormed() {
        expectCode(() -> f.bookings.requestWithKey(passenger, trip, 1, null), "IDEMPOTENCY_KEY_REQUIRED");
        expectCode(() -> f.bookings.requestWithKey(passenger, trip, 1, "  "), "IDEMPOTENCY_KEY_REQUIRED");
        expectCode(() -> f.bookings.requestWithKey(passenger, trip, 1, "short"), "IDEMPOTENCY_KEY_INVALID");
        expectCode(() -> f.bookings.requestWithKey(passenger, trip, 1, "has space in it"), "IDEMPOTENCY_KEY_INVALID");
        expectCode(() -> f.bookings.requestWithKey(passenger, trip, 1, "a".repeat(129)), "IDEMPOTENCY_KEY_INVALID");
        assertThat(f.bookingCount()).isZero();
    }

    @Test
    void parallelIdenticalRequestsCreateOneBookingAndAllGetTheSameBody() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<IdempotencyService.Result>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return f.bookings.requestWithKey(passenger, trip, 2, KEY);
                }));
            }
            start.countDown();
            List<IdempotencyService.Result> results = new ArrayList<>();
            for (Future<IdempotencyService.Result> fut : futures) {
                results.add(fut.get());
            }

            assertThat(results).extracting(IdempotencyService.Result::body).containsOnly(results.get(0).body());
            assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
            assertThat(f.bookingCount()).isEqualTo(1L);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void fingerprintIgnoresFormattingAndSeparatesFields() {
        assertThat(IdempotencyService.fingerprint("A", "1", "2")).isEqualTo(IdempotencyService.fingerprint("A", "1", "2"));
        assertThat(IdempotencyService.fingerprint("A", "1", "2")).isNotEqualTo(IdempotencyService.fingerprint("A", "12", ""));
    }
}
