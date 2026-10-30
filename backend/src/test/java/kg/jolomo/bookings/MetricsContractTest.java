package kg.jolomo.bookings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SP07-03: контракт метрик на фикстуре событий. Числители и знаменатели считаются только по domain_events, как записано в
 * docs/booking-lifecycle-and-metrics.md. Если определение меняется, меняется и этот тест, и документ.
 */
class MetricsContractTest extends PostgisTestBase {

    private BookingFixture f;

    @BeforeEach
    void setUp() {
        f = new BookingFixture(jdbc, tx);
    }

    /** Брони с событием типа type. */
    private long bookingsWith(String type) {
        return jdbc.sql("SELECT count(DISTINCT aggregate_id) FROM domain_events WHERE aggregate_type = 'BOOKING' AND event_type = :t")
                .param("t", type)
                .query(Long.class)
                .single();
    }

    /** Брони с событием типа type, у которого предыдущее состояние равно from. */
    private long bookingsWith(String type, String from) {
        return jdbc.sql("""
                        SELECT count(DISTINCT aggregate_id) FROM domain_events
                        WHERE aggregate_type = 'BOOKING' AND event_type = :t AND payload->>'from' = :f
                        """)
                .param("t", type)
                .param("f", from)
                .query(Long.class)
                .single();
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    @Test
    void metricsFollowTheDeclaredNumeratorsAndDenominators() {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 6);
        BookingService.Booking p1 = f.bookings.request(f.user(), trip, 1);
        BookingService.Booking p2 = f.bookings.request(f.user(), trip, 1);
        BookingService.Booking p3 = f.bookings.request(f.user(), trip, 1);
        BookingService.Booking p4 = f.bookings.request(f.user(), trip, 1);
        UUID passenger5 = f.user();
        BookingService.Booking p5 = f.bookings.request(passenger5, trip, 1);
        UUID passenger6 = f.user();
        BookingService.Booking p6 = f.bookings.request(passenger6, trip, 1);
        f.bookings.accept(driver, p1.id());
        f.bookings.accept(driver, p2.id());
        f.bookings.accept(driver, p3.id());
        f.bookings.reject(driver, p4.id());
        f.bookings.accept(driver, p5.id());
        f.bookings.cancel(passenger5, p5.id());      // отмена после принятия
        f.bookings.cancel(passenger6, p6.id());      // отмена до решения: в метрики решений не входит
        f.clock.advance(Duration.ofDays(2));
        f.bookings.complete(driver, p1.id());
        f.bookings.complete(driver, p2.id());
        f.bookings.markNoShow(driver, p3.id());

        long requested = bookingsWith("BOOKING_REQUESTED");
        long accepted = bookingsWith("BOOKING_ACCEPTED");
        long rejected = bookingsWith("BOOKING_REJECTED");
        long completed = bookingsWith("BOOKING_COMPLETED");
        long noShow = bookingsWith("BOOKING_NO_SHOW");
        long cancelledAfterAccept = bookingsWith("BOOKING_CANCELLED", "ACCEPTED");

        assertThat(requested).isEqualTo(6);
        // Доля принятых запросов: 4 принятых из 5 решений (4 принятия и 1 отказ).
        assertThat(accepted).isEqualTo(4);
        assertThat(rejected).isEqualTo(1);
        assertThat(ratio(accepted, accepted + rejected)).isEqualTo(0.8);
        // Доля завершённых и доля неявок: общий знаменатель, всё отмеченное после отправления.
        assertThat(completed).isEqualTo(2);
        assertThat(noShow).isEqualTo(1);
        assertThat(ratio(completed, completed + noShow)).isEqualTo(2.0 / 3.0);
        assertThat(ratio(noShow, completed + noShow)).isEqualTo(1.0 / 3.0);
        // Доля отмен после принятия: 1 из 4 принятых. Отмена запроса до решения сюда не попала.
        assertThat(cancelledAfterAccept).isEqualTo(1);
        assertThat(ratio(cancelledAfterAccept, accepted)).isEqualTo(0.25);
    }

    @Test
    void emptyDenominatorMeansUndefinedNotZeroNorHundredPercent() {
        assertThat(ratio(0, bookingsWith("BOOKING_COMPLETED") + bookingsWith("BOOKING_NO_SHOW"))).isNull();
    }

    @Test
    void anAcceptedBookingNobodyMarkedIsOutsideTheCompletionDenominator() {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 2);
        f.bookings.accept(driver, f.bookings.request(f.user(), trip, 1).id());
        f.clock.advance(Duration.ofDays(5));

        assertThat(bookingsWith("BOOKING_ACCEPTED")).isEqualTo(1);
        assertThat(bookingsWith("BOOKING_COMPLETED") + bookingsWith("BOOKING_NO_SHOW")).isZero();
    }

    @Test
    void everyBookingEventCarriesTheFieldsTheDefinitionsRead() {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 2);
        BookingService.Booking b = f.bookings.accept(driver, f.bookings.request(f.user(), trip, 1).id());
        f.clock.advance(Duration.ofDays(2));
        f.bookings.complete(driver, b.id());

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM domain_events WHERE aggregate_type = 'BOOKING'
                          AND (payload->>'to' IS NULL OR payload->>'tripId' IS NULL OR actor_user_id IS NULL)
                        """).query(Long.class).single()).isZero();
    }
}
