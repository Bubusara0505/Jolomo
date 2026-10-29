package kg.jolomo.parcels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kg.jolomo.shared.ApiException;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP08-02: гонки custody на настоящем PostGIS. Свидетельство пишется ровно один раз, итог всегда согласован. */
class ParcelCustodyRaceTest extends PostgisTestBase {

    private static final int THREADS = 8;

    private ParcelFixture f;
    private UUID driver;
    private UUID trip;

    @BeforeEach
    void setUp() {
        f = new ParcelFixture(jdbc, tx);
        f.loadParcelObligations();
        driver = f.driver();
        trip = f.publishedTrip(driver, 3);
    }

    private List<String> raceAll(List<Callable<Object>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<Object> action : actions) {
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

    private long auditRows(UUID parcel, String action) {
        return jdbc.sql("SELECT count(*) FROM audit_events WHERE subject_id = :id AND action = :a")
                .param("id", parcel).param("a", action).query(Long.class).single();
    }

    @Test
    void manyParallelPickupsGiveOneTransitionAndOneEvidenceRow() throws Exception {
        ParcelService.Parcel p = f.parcels.create(f.user(), f.valid(trip));
        f.parcels.accept(driver, p.id());
        List<Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(() -> f.parcels.confirmPickup(driver, p.id()));
        }

        assertThat(raceAll(actions)).containsOnly("OK");

        assertThat(f.parcels.find(p.id()).orElseThrow().status()).isEqualTo(ParcelStatus.PICKUP_CONFIRMED);
        assertThat(auditRows(p.id(), "PARCEL_PICKUP_CONFIRMED")).isEqualTo(1L);
    }

    @Test
    void pickupRacingWithTheSendersCancelEndsInOneConsistentState() throws Exception {
        ParcelService.Parcel p = f.parcels.create(f.user(), f.valid(trip));
        f.parcels.accept(driver, p.id());
        List<Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            actions.add(() -> f.parcels.confirmPickup(driver, p.id()));
            actions.add(() -> f.parcels.cancel(p.senderId(), p.id()));
        }

        raceAll(actions);

        ParcelStatus status = f.parcels.find(p.id()).orElseThrow().status();
        assertThat(status).isIn(ParcelStatus.PICKUP_CONFIRMED, ParcelStatus.CANCELLED);
        // Слот занят, только пока посылка у водителя; свидетельство есть, только если custody началась.
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(status == ParcelStatus.PICKUP_CONFIRMED ? 2 : 3);
        assertThat(auditRows(p.id(), "PARCEL_PICKUP_CONFIRMED")).isEqualTo(status == ParcelStatus.PICKUP_CONFIRMED ? 1L : 0L);
    }

    @Test
    void parallelDifferentStepsNeverSkipTheChain() throws Exception {
        ParcelService.Parcel p = f.parcels.create(f.user(), f.valid(trip));
        f.parcels.accept(driver, p.id());
        List<Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            actions.add(() -> f.parcels.arrive(driver, p.id()));
            actions.add(() -> f.parcels.dispatch(driver, p.id()));
            actions.add(() -> f.parcels.confirmPickup(driver, p.id()));
        }

        raceAll(actions);

        // Какой бы порядок ни выпал, каждое пройденное состояние имеет ровно одно свидетельство, а пропусков нет.
        long pickup = auditRows(p.id(), "PARCEL_PICKUP_CONFIRMED");
        long transit = auditRows(p.id(), "PARCEL_IN_TRANSIT");
        long pending = auditRows(p.id(), "PARCEL_DELIVERY_PENDING");
        assertThat(pickup).isEqualTo(1L);
        assertThat(transit).isLessThanOrEqualTo(pickup);
        assertThat(pending).isLessThanOrEqualTo(transit);
    }
}
