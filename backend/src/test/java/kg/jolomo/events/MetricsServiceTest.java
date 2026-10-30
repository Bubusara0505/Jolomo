package kg.jolomo.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import kg.jolomo.bookings.BookingService;
import kg.jolomo.parcels.ParcelService;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP12-01: метрики пилота считаются по объявленным числителям и знаменателям на фикстуре событий. */
class MetricsServiceTest extends PostgisTestBase {

    private static final Instant FOREVER = Instant.parse("2100-01-01T00:00:00Z");

    private PilotFixture f;
    private MetricsService metrics;

    @BeforeEach
    void setUp() {
        f = new PilotFixture(jdbc, tx);
        metrics = new MetricsService(jdbc);
    }

    private Map<String, MetricsService.Metric> all(Instant from, Instant to) {
        return metrics.compute(from, to).stream().collect(Collectors.toMap(MetricsService.Metric::code, m -> m));
    }

    private void assertMetric(Map<String, MetricsService.Metric> m, String code, long numerator, long denominator) {
        assertThat(m.get(code)).as(code).isNotNull();
        assertThat(m.get(code).numerator()).as(code + " числитель").isEqualTo(numerator);
        assertThat(m.get(code).denominator()).as(code + " знаменатель").isEqualTo(denominator);
    }

    /** Шесть броней и шесть посылок с известными исходами. */
    private void scenario() {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 6, 6);

        UUID pass5 = f.user();
        UUID pass6 = f.user();
        BookingService.Booking b1 = f.bookings.request(f.user(), trip, 1);
        BookingService.Booking b2 = f.bookings.request(f.user(), trip, 1);
        BookingService.Booking b3 = f.bookings.request(f.user(), trip, 1);
        BookingService.Booking b4 = f.bookings.request(f.user(), trip, 1);
        BookingService.Booking b5 = f.bookings.request(pass5, trip, 1);
        BookingService.Booking b6 = f.bookings.request(pass6, trip, 1);
        f.bookings.accept(driver, b1.id());
        f.bookings.accept(driver, b2.id());
        f.bookings.accept(driver, b3.id());
        f.bookings.reject(driver, b4.id());
        f.bookings.accept(driver, b5.id());
        f.bookings.cancel(pass5, b5.id());   // отмена после принятия
        f.bookings.cancel(pass6, b6.id());   // отмена до решения: в метрики решений не входит

        ParcelService.Parcel[] ps = new ParcelService.Parcel[6];
        UUID[] ids = new UUID[6];
        for (int i = 0; i < 6; i++) {
            ps[i] = f.parcels.create(f.user(), f.parcelFor(trip));
            ids[i] = ps[i].id();
        }
        f.parcels.accept(driver, ids[0]);
        f.parcels.accept(driver, ids[1]);
        f.parcels.accept(driver, ids[2]);
        f.parcels.accept(driver, ids[3]);
        f.parcels.reject(driver, ids[4]);
        f.parcels.cancel(ps[5].senderId(), ids[5]);   // отмена до решения
        for (int i = 0; i < 3; i++) {
            f.parcels.confirmPickup(driver, ids[i]);
            f.parcels.dispatch(driver, ids[i]);
            f.parcels.arrive(driver, ids[i]);
        }
        // Исход получателя пишет доставка (SP09-02, покрыта своими тестами); здесь нужны только её события.
        f.events.append("PARCEL", ids[0], "PARCEL_DELIVERED", null, Map.of("from", "DELIVERY_PENDING", "to", "DELIVERED"));
        f.events.append("PARCEL", ids[1], "PARCEL_DELIVERED", null, Map.of("from", "DELIVERY_PENDING", "to", "DELIVERED"));
        f.events.append("PARCEL", ids[2], "PARCEL_VERIFICATION_LOCKED", null,
                Map.of("from", "DELIVERY_PENDING", "to", "VERIFICATION_LOCKED"));

        f.clock.advance(Duration.ofDays(2));
        f.bookings.complete(driver, b1.id());
        f.bookings.complete(driver, b2.id());
        f.bookings.markNoShow(driver, b3.id());
    }

    @Test
    void sevenMetricsWithTheDeclaredNumeratorsAndDenominators() {
        scenario();

        Map<String, MetricsService.Metric> m = all(Instant.EPOCH, FOREVER);

        assertThat(m).hasSize(7);
        assertMetric(m, "BOOKING_ACCEPTANCE_RATE", 4, 5);
        assertMetric(m, "BOOKING_COMPLETION_RATE", 2, 3);
        assertMetric(m, "BOOKING_NO_SHOW_RATE", 1, 3);
        assertMetric(m, "BOOKING_CANCEL_AFTER_ACCEPT_RATE", 1, 4);
        assertMetric(m, "PARCEL_ACCEPTANCE_RATE", 4, 5);
        assertMetric(m, "PARCEL_DELIVERY_RATE", 2, 3);
        assertMetric(m, "PARCEL_VERIFICATION_LOCK_RATE", 1, 3);
        assertThat(m.get("BOOKING_ACCEPTANCE_RATE").value()).isEqualTo(0.8);
        assertThat(m.get("BOOKING_COMPLETION_RATE").value()).isEqualTo(2.0 / 3.0);
        assertThat(m.get("BOOKING_CANCEL_AFTER_ACCEPT_RATE").value()).isEqualTo(0.25);
    }

    @Test
    void anEmptyDenominatorMeansUndefinedNotZeroNorHundredPercent() {
        for (MetricsService.Metric m : metrics.compute(Instant.EPOCH, FOREVER)) {
            assertThat(m.defined()).as(m.code()).isFalse();
            assertThat(m.value()).as(m.code()).isNull();
        }
    }

    @Test
    void theCodesAreExactlyTheSevenDeclaredOnesInOrder() {
        assertThat(MetricsService.codes()).containsExactly(
                "BOOKING_ACCEPTANCE_RATE", "BOOKING_COMPLETION_RATE", "BOOKING_NO_SHOW_RATE",
                "BOOKING_CANCEL_AFTER_ACCEPT_RATE", "PARCEL_ACCEPTANCE_RATE", "PARCEL_DELIVERY_RATE",
                "PARCEL_VERIFICATION_LOCK_RATE");
    }

    @Test
    void theCohortIsDefinedByTheRequestDateButOutcomesCountWheneverTheyHappen() {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 4, 0);
        Instant t0 = f.clock.instant();
        BookingService.Booking first = f.bookings.request(f.user(), trip, 1);
        f.bookings.accept(driver, first.id());
        f.clock.advance(Duration.ofDays(10));
        Instant t1 = f.clock.instant();
        UUID late = f.publishedTrip(driver, 4, 0);
        f.bookings.accept(driver, f.bookings.request(f.user(), late, 1).id());
        f.bookings.reject(driver, f.bookings.request(f.user(), late, 1).id());

        Map<String, MetricsService.Metric> firstCohort = all(t0.minusSeconds(60), t0.plusSeconds(60));
        Map<String, MetricsService.Metric> secondCohort = all(t1.minusSeconds(60), t1.plusSeconds(60));
        Map<String, MetricsService.Metric> everyone = all(Instant.EPOCH, FOREVER);

        assertMetric(firstCohort, "BOOKING_ACCEPTANCE_RATE", 1, 1);
        assertMetric(secondCohort, "BOOKING_ACCEPTANCE_RATE", 1, 2);
        assertMetric(everyone, "BOOKING_ACCEPTANCE_RATE", 2, 3);
        // Окно без запросов даёт неопределённую метрику.
        assertThat(all(t0.plus(Duration.ofDays(3)), t0.plus(Duration.ofDays(4))).get("BOOKING_ACCEPTANCE_RATE").defined()).isFalse();
    }

    @Test
    void anOutcomeAfterTheWindowStillBelongsToTheCohortOfItsRequest() {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 2, 0);
        Instant t0 = f.clock.instant();
        BookingService.Booking b = f.bookings.request(f.user(), trip, 1);
        f.clock.advance(Duration.ofHours(12));
        f.bookings.accept(driver, b.id());

        assertMetric(all(t0.minusSeconds(60), t0.plusSeconds(60)), "BOOKING_ACCEPTANCE_RATE", 1, 1);
    }

    @Test
    void aBookingIsCountedOnceEvenIfItHasSeveralEventsOfTheSameMeaning() {
        UUID driver = f.driver();
        UUID trip = f.publishedTrip(driver, 2, 0);
        BookingService.Booking b = f.bookings.request(f.user(), trip, 1);
        f.bookings.accept(driver, b.id());
        f.bookings.accept(driver, b.id());   // повтор принятия событий не добавляет, но проверим и прямую вставку
        f.events.append("BOOKING", b.id(), "BOOKING_ACCEPTED", driver, Map.of("from", "REQUESTED", "to", "ACCEPTED"));

        assertMetric(all(Instant.EPOCH, FOREVER), "BOOKING_ACCEPTANCE_RATE", 1, 1);
    }

    @Test
    void metricsOnlyReadEventTypesThatAreRegistered() {
        assertThat(EventRegistry.types()).containsAll(MetricsService.eventTypesUsed());
        List<String> unknown = MetricsService.eventTypesUsed().stream().filter(t -> !EventRegistry.known(t)).toList();
        assertThat(unknown).isEmpty();
    }
}
