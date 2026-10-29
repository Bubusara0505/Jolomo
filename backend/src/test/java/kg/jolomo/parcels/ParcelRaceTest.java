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

/** T-02: гонки на настоящем PostGIS. У последнего слота посылки один победитель, слоты не списываются и не возвращаются дважды. */
class ParcelRaceTest extends PostgisTestBase {

    private static final int THREADS = 8;

    private ParcelFixture f;
    private UUID driver;

    @BeforeEach
    void setUp() {
        f = new ParcelFixture(jdbc, tx);
        f.loadParcelObligations();
        driver = f.driver();
    }

    /** Запускает все действия одновременно и возвращает исходы: OK или код ApiException. */
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

    private List<UUID> requestedParcels(UUID trip, int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(f.parcels.create(f.user(), f.valid(trip)).id());
        }
        return ids;
    }

    private long accepted() {
        return jdbc.sql("SELECT count(*) FROM parcel_requests WHERE status = 'ACCEPTED'").query(Long.class).single();
    }

    @Test
    void lastSlotHasExactlyOneWinnerWhenManyParcelsAreAcceptedAtOnce() throws Exception {
        UUID trip = f.publishedTrip(driver, 1);
        List<Callable<Object>> actions = new ArrayList<>();
        for (UUID id : requestedParcels(trip, THREADS)) {
            actions.add(() -> f.parcels.accept(driver, id));
        }

        List<String> codes = raceAll(actions);

        assertThat(codes.stream().filter("OK"::equals)).hasSize(1);
        assertThat(codes.stream().filter("NO_PARCEL_CAPACITY"::equals)).hasSize(THREADS - 1);
        assertThat(f.parcelSlotsAvailable(trip)).isZero();
        assertThat(accepted()).isEqualTo(1L);
    }

    @Test
    void severalSlotsAreNeverOversoldUnderContention() throws Exception {
        UUID trip = f.publishedTrip(driver, 3);
        List<Callable<Object>> actions = new ArrayList<>();
        for (UUID id : requestedParcels(trip, THREADS)) {
            actions.add(() -> f.parcels.accept(driver, id));
        }

        List<String> codes = raceAll(actions);

        assertThat(codes.stream().filter("OK"::equals)).hasSize(3);
        assertThat(f.parcelSlotsAvailable(trip)).isZero();
        assertThat(accepted()).isEqualTo(3L);
    }

    @Test
    void acceptingTheSameParcelConcurrentlyTakesOneSlot() throws Exception {
        UUID trip = f.publishedTrip(driver, 3);
        UUID id = requestedParcels(trip, 1).get(0);
        List<Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(() -> f.parcels.accept(driver, id));
        }

        assertThat(raceAll(actions)).containsOnly("OK");

        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(2);
    }

    @Test
    void cancellingTheSameAcceptedParcelConcurrentlyFreesOneSlot() throws Exception {
        UUID trip = f.publishedTrip(driver, 3);
        ParcelService.Parcel p = f.parcels.create(f.user(), f.valid(trip));
        f.parcels.accept(driver, p.id());
        List<Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actions.add(() -> f.parcels.cancel(p.senderId(), p.id()));
        }

        assertThat(raceAll(actions)).containsOnly("OK");

        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(3);
    }

    @Test
    void acceptAndCancelRacingOnTheSameParcelLeaveConsistentSlots() throws Exception {
        UUID trip = f.publishedTrip(driver, 3);
        ParcelService.Parcel p = f.parcels.create(f.user(), f.valid(trip));
        List<Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            actions.add(() -> f.parcels.accept(driver, p.id()));
            actions.add(() -> f.parcels.cancel(p.senderId(), p.id()));
        }

        raceAll(actions);

        ParcelStatus status = f.parcels.find(p.id()).orElseThrow().status();
        assertThat(status).isIn(ParcelStatus.ACCEPTED, ParcelStatus.CANCELLED);
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(status == ParcelStatus.ACCEPTED ? 2 : 3);
    }

    @Test
    void aCancelFreeingTheLastSlotRacingWithAnAcceptKeepsSlotsConsistent() throws Exception {
        UUID trip = f.publishedTrip(driver, 1);
        ParcelService.Parcel holder = f.parcels.create(f.user(), f.valid(trip));
        ParcelService.Parcel waiting = f.parcels.create(f.user(), f.valid(trip));
        f.parcels.accept(driver, holder.id());
        List<Callable<Object>> actions = new ArrayList<>();
        actions.add(() -> f.parcels.cancel(holder.senderId(), holder.id()));
        actions.add(() -> f.parcels.accept(driver, waiting.id()));

        raceAll(actions);

        // Какой бы порядок ни выпал, слотов занято ровно по числу принятых.
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(1 - (int) accepted());
        assertThat(accepted()).isLessThanOrEqualTo(1L);
    }

    @Test
    void tripCancellationRacingWithCreationsLeavesNoOpenParcelOnTheCancelledTrip() throws Exception {
        UUID trip = f.publishedTrip(driver, 8);
        List<Callable<Object>> actions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID sender = f.user();
            actions.add(() -> f.parcels.create(sender, f.valid(trip)));
        }
        actions.add(() -> f.trips.cancel(driver, trip));

        raceAll(actions);

        assertThat(jdbc.sql("SELECT count(*) FROM parcel_requests WHERE trip_id = :t AND status IN ('REQUESTED', 'ACCEPTED')")
                        .param("t", trip)
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
