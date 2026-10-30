package kg.jolomo.bookings;

import static kg.jolomo.bookings.BookingFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SP07-03: таблица приёмки. Каждая пара «состояние брони и действие» либо даёт ожидаемое состояние, либо 409
 * INVALID_BOOKING_STATE. Таблица совпадает с docs/booking-lifecycle-and-metrics.md.
 */
class BookingTransitionMatrixTest extends PostgisTestBase {

    private enum Action { ACCEPT, REJECT, CANCEL_PASSENGER, CANCEL_DRIVER, COMPLETE, NO_SHOW }

    /** expected == null значит «запрещено». */
    private record Case(BookingStatus from, Action action, BookingStatus expected) {}

    private static final BookingStatus RQ = BookingStatus.REQUESTED;
    private static final BookingStatus AC = BookingStatus.ACCEPTED;
    private static final BookingStatus RJ = BookingStatus.REJECTED;
    private static final BookingStatus CA = BookingStatus.CANCELLED;
    private static final BookingStatus CO = BookingStatus.COMPLETED;
    private static final BookingStatus NS = BookingStatus.NO_SHOW;
    private static final BookingStatus RR = BookingStatus.REVIEW_REQUIRED;

    private static List<Case> matrix() {
        List<Case> m = new ArrayList<>();
        row(m, RQ, AC, RJ, CA, null, null, null);
        row(m, AC, AC, null, CA, CA, CO, NS);
        row(m, RJ, null, RJ, null, null, null, null);
        row(m, CA, null, null, CA, CA, null, null);
        row(m, CO, null, null, null, null, CO, null);
        row(m, NS, null, null, null, null, null, NS);
        row(m, RR, null, null, null, null, null, null);
        return m;
    }

    private static void row(List<Case> m, BookingStatus from, BookingStatus accept, BookingStatus reject, BookingStatus cancelP,
                            BookingStatus cancelD, BookingStatus complete, BookingStatus noShow) {
        m.add(new Case(from, Action.ACCEPT, accept));
        m.add(new Case(from, Action.REJECT, reject));
        m.add(new Case(from, Action.CANCEL_PASSENGER, cancelP));
        m.add(new Case(from, Action.CANCEL_DRIVER, cancelD));
        m.add(new Case(from, Action.COMPLETE, complete));
        m.add(new Case(from, Action.NO_SHOW, noShow));
    }

    private BookingFixture f;
    private UUID driver;

    @BeforeEach
    void setUp() {
        f = new BookingFixture(jdbc, tx);
        driver = f.driver();
    }

    /** Приводит свежую бронь в нужное состояние. Для состояний после отправления время сдвигается вперёд. */
    private BookingService.Booking bookingIn(BookingStatus state, UUID passenger) {
        UUID trip = f.publishedTrip(driver, 4);
        BookingService.Booking b = f.bookings.request(passenger, trip, 1);
        switch (state) {
            case REQUESTED -> { }
            case ACCEPTED -> f.bookings.accept(driver, b.id());
            case REJECTED -> f.bookings.reject(driver, b.id());
            case CANCELLED -> f.bookings.cancel(passenger, b.id());
            case COMPLETED -> {
                f.bookings.accept(driver, b.id());
                f.clock.advance(Duration.ofDays(2));
                f.bookings.complete(driver, b.id());
            }
            case NO_SHOW -> {
                f.bookings.accept(driver, b.id());
                f.clock.advance(Duration.ofDays(2));
                f.bookings.markNoShow(driver, b.id());
            }
            case REVIEW_REQUIRED -> jdbc.sql("UPDATE bookings SET status = 'REVIEW_REQUIRED' WHERE id = :id")
                    .param("id", b.id()).update();
        }
        return f.bookings.find(b.id()).orElseThrow();
    }

    /** Для ACCEPTED действия complete и no-show проверяются после отправления, остальные до. */
    private BookingService.Booking apply(Case c, BookingService.Booking b, UUID passenger) {
        BiFunction<UUID, UUID, BookingService.Booking> run = switch (c.action()) {
            case ACCEPT -> f.bookings::accept;
            case REJECT -> f.bookings::reject;
            case CANCEL_PASSENGER, CANCEL_DRIVER -> f.bookings::cancel;
            case COMPLETE -> f.bookings::complete;
            case NO_SHOW -> f.bookings::markNoShow;
        };
        UUID actor = c.action() == Action.CANCEL_PASSENGER ? passenger : driver;
        if (c.from() == AC && (c.action() == Action.COMPLETE || c.action() == Action.NO_SHOW)) {
            f.clock.advance(Duration.ofDays(2));
        }
        return run.apply(actor, b.id());
    }

    @Test
    void everyStateAndActionFollowsTheTable() {
        for (Case c : matrix()) {
            UUID passenger = f.user();
            BookingService.Booking b = bookingIn(c.from(), passenger);
            assertThat(b.status()).as("исходное состояние %s", c.from()).isEqualTo(c.from());
            String label = c.from() + " + " + c.action();

            if (c.expected() == null) {
                expectCode(() -> apply(c, b, passenger), "INVALID_BOOKING_STATE");
                assertThat(f.bookings.find(b.id()).orElseThrow().status()).as(label).isEqualTo(c.from());
            } else {
                assertThat(apply(c, b, passenger).status()).as(label).isEqualTo(c.expected());
            }
        }
    }

    @Test
    void theTableCoversEveryStatusAndEveryActionExactlyOnce() {
        List<Case> m = matrix();

        assertThat(m).hasSize(BookingStatus.values().length * Action.values().length);
        assertThat(m.stream().map(c -> c.from() + ":" + c.action()).distinct().count()).isEqualTo(m.size());
    }
}
