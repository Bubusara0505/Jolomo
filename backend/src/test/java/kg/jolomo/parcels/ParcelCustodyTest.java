package kg.jolomo.parcels;

import static kg.jolomo.parcels.ParcelFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore.StoredEvent;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP08-02: custody. Только назначенный проверенный водитель, строго по цепочке, и каждый переход оставляет свидетельство. */
class ParcelCustodyTest extends PostgisTestBase {

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

    private ParcelService.Parcel accepted() {
        ParcelService.Parcel p = f.parcels.create(f.user(), f.valid(trip));
        return f.parcels.accept(driver, p.id());
    }

    private long auditRows(UUID parcel) {
        return jdbc.sql("SELECT count(*) FROM audit_events WHERE subject_type = 'PARCEL' AND subject_id = :id")
                .param("id", parcel).query(Long.class).single();
    }

    private Instant auditTime(UUID parcel, String action) {
        return jdbc.sql("SELECT occurred_at FROM audit_events WHERE subject_id = :id AND action = :a")
                .param("id", parcel).param("a", action)
                .query((rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
    }

    @Test
    void theFullChainRunsInOrderAndKeepsTheSlotTaken() {
        ParcelService.Parcel p = accepted();

        assertThat(f.parcels.confirmPickup(driver, p.id()).status()).isEqualTo(ParcelStatus.PICKUP_CONFIRMED);
        assertThat(f.parcels.dispatch(driver, p.id()).status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(f.parcels.arrive(driver, p.id()).status()).isEqualTo(ParcelStatus.DELIVERY_PENDING);

        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(2);
        assertThat(f.events.forAggregate("PARCEL", p.id())).extracting(StoredEvent::eventType)
                .containsExactly("PARCEL_REQUESTED", "PARCEL_ACCEPTED", "PARCEL_PICKUP_CONFIRMED",
                        "PARCEL_IN_TRANSIT", "PARCEL_DELIVERY_PENDING");
    }

    @Test
    void everyTransitionWritesAnAuditRowWithWhoWhenAndFromTo() {
        ParcelService.Parcel p = accepted();
        Instant t1 = f.clock.instant();
        f.parcels.confirmPickup(driver, p.id());
        f.clock.advance(Duration.ofMinutes(5));
        Instant t2 = f.clock.instant();
        f.parcels.dispatch(driver, p.id());
        f.clock.advance(Duration.ofMinutes(5));
        Instant t3 = f.clock.instant();
        f.parcels.arrive(driver, p.id());

        assertThat(auditTime(p.id(), "PARCEL_PICKUP_CONFIRMED")).isEqualTo(t1);
        assertThat(auditTime(p.id(), "PARCEL_IN_TRANSIT")).isEqualTo(t2);
        assertThat(auditTime(p.id(), "PARCEL_DELIVERY_PENDING")).isEqualTo(t3);
        var rows = jdbc.sql("""
                        SELECT actor_user_id, from_state, to_state, reason_code FROM audit_events
                        WHERE subject_id = :id AND action LIKE 'PARCEL_%' ORDER BY occurred_at
                        """)
                .param("id", p.id())
                .query((rs, i) -> List.of(String.valueOf(rs.getObject(1, UUID.class)), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
        assertThat(rows).containsExactly(
                List.of(driver.toString(), "ACCEPTED", "PICKUP_CONFIRMED", "CUSTODY_TRANSITION"),
                List.of(driver.toString(), "PICKUP_CONFIRMED", "IN_TRANSIT", "CUSTODY_TRANSITION"),
                List.of(driver.toString(), "IN_TRANSIT", "DELIVERY_PENDING", "CUSTODY_TRANSITION"));
    }

    @Test
    void aRepeatedTransitionIsSafeAndWritesNoNewEvidence() {
        ParcelService.Parcel p = accepted();
        f.parcels.confirmPickup(driver, p.id());
        long rows = auditRows(p.id());
        int events = f.events.forAggregate("PARCEL", p.id()).size();

        assertThat(f.parcels.confirmPickup(driver, p.id()).status()).isEqualTo(ParcelStatus.PICKUP_CONFIRMED);
        assertThat(f.parcels.confirmPickup(driver, p.id()).status()).isEqualTo(ParcelStatus.PICKUP_CONFIRMED);

        assertThat(auditRows(p.id())).isEqualTo(rows);
        assertThat(f.events.forAggregate("PARCEL", p.id())).hasSize(events);
    }

    @Test
    void stepsCannotBeSkippedOrRunBackwards() {
        ParcelService.Parcel p = accepted();

        expectCode(() -> f.parcels.dispatch(driver, p.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.arrive(driver, p.id()), "INVALID_PARCEL_STATE");
        f.parcels.confirmPickup(driver, p.id());
        expectCode(() -> f.parcels.arrive(driver, p.id()), "INVALID_PARCEL_STATE");
        f.parcels.dispatch(driver, p.id());
        expectCode(() -> f.parcels.confirmPickup(driver, p.id()), "INVALID_PARCEL_STATE");
        f.parcels.arrive(driver, p.id());
        expectCode(() -> f.parcels.dispatch(driver, p.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.confirmPickup(driver, p.id()), "INVALID_PARCEL_STATE");
        // Повтор последнего шага безопасен.
        assertThat(f.parcels.arrive(driver, p.id()).status()).isEqualTo(ParcelStatus.DELIVERY_PENDING);
    }

    @Test
    void parcelsThatWereNeverAcceptedCannotEnterCustody() {
        ParcelService.Parcel requested = f.parcels.create(f.user(), f.valid(trip));
        ParcelService.Parcel rejected = f.parcels.create(f.user(), f.valid(trip));
        f.parcels.reject(driver, rejected.id());
        ParcelService.Parcel cancelled = accepted();
        f.parcels.cancel(cancelled.senderId(), cancelled.id());

        expectCode(() -> f.parcels.confirmPickup(driver, requested.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.confirmPickup(driver, rejected.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.confirmPickup(driver, cancelled.id()), "INVALID_PARCEL_STATE");
        assertThat(auditRows(requested.id()) + auditRows(rejected.id()) + auditRows(cancelled.id())).isZero();
    }

    @Test
    void onlyTheAssignedDriverMovesCustodyAndEveryoneElseGetsNotFound() {
        ParcelService.Parcel p = accepted();
        UUID otherApprovedDriver = f.driver();
        UUID stranger = f.user();

        expectCode(() -> f.parcels.confirmPickup(otherApprovedDriver, p.id()), "PARCEL_NOT_FOUND");
        expectCode(() -> f.parcels.confirmPickup(p.senderId(), p.id()), "PARCEL_NOT_FOUND");
        expectCode(() -> f.parcels.confirmPickup(stranger, p.id()), "PARCEL_NOT_FOUND");
        expectCode(() -> f.parcels.confirmPickup(driver, UUID.randomUUID()), "PARCEL_NOT_FOUND");
        f.parcels.confirmPickup(driver, p.id());
        expectCode(() -> f.parcels.dispatch(p.senderId(), p.id()), "PARCEL_NOT_FOUND");
        expectCode(() -> f.parcels.arrive(otherApprovedDriver, p.id()), "PARCEL_NOT_FOUND");
        assertThat(f.parcels.find(p.id()).orElseThrow().status()).isEqualTo(ParcelStatus.PICKUP_CONFIRMED);
    }

    @Test
    void aDriverWhoIsNoLongerVerifiedCannotMoveCustodyAndNothingIsWritten() {
        ParcelService.Parcel p = accepted();
        f.parcels.confirmPickup(driver, p.id());
        long rows = auditRows(p.id());
        jdbc.sql("UPDATE driver_profiles SET status = 'SUSPENDED' WHERE user_id = :u").param("u", driver).update();

        expectCode(() -> f.parcels.dispatch(driver, p.id()), "NOT_APPROVED");

        assertThat(f.parcels.find(p.id()).orElseThrow().status()).isEqualTo(ParcelStatus.PICKUP_CONFIRMED);
        assertThat(auditRows(p.id())).isEqualTo(rows);
    }

    @Test
    void pickupNeedsAPublishedTripButLaterStepsRecordPhysicalFactsRegardless() {
        ParcelService.Parcel early = accepted();
        ParcelService.Parcel started = accepted();
        f.parcels.confirmPickup(driver, started.id());
        jdbc.sql("UPDATE trips SET status = 'COMPLETED' WHERE id = :id").param("id", trip).update();

        expectCode(() -> f.parcels.confirmPickup(driver, early.id()), "INVALID_TRIP_STATE");
        assertThat(f.parcels.dispatch(driver, started.id()).status()).isEqualTo(ParcelStatus.IN_TRANSIT);
    }

    @Test
    void custodyContinuesEvenIfTheObligationsAreSwitchedOffLater() {
        ParcelService.Parcel p = accepted();
        f.parcels.confirmPickup(driver, p.id());
        f.obligations.disable("PARCEL_CREATE", ParcelFixture.ADMIN, "LEGAL_REVIEW");

        assertThat(f.parcels.dispatch(driver, p.id()).status()).isEqualTo(ParcelStatus.IN_TRANSIT);
        assertThat(f.parcels.arrive(driver, p.id()).status()).isEqualTo(ParcelStatus.DELIVERY_PENDING);
    }

    @Test
    void onceInCustodyTheSenderCannotCancelAndTheSlotStaysTaken() {
        ParcelService.Parcel p = accepted();
        f.parcels.confirmPickup(driver, p.id());

        expectCode(() -> f.parcels.cancel(p.senderId(), p.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.cancel(driver, p.id()), "INVALID_PARCEL_STATE");

        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(2);
    }

    @Test
    void cancellingTheTripDoesNotSilentlyCancelAParcelAlreadyWithTheDriver() {
        ParcelService.Parcel inCustody = accepted();
        ParcelService.Parcel notYet = accepted();
        f.parcels.confirmPickup(driver, inCustody.id());

        f.trips.cancel(driver, trip);

        assertThat(f.parcels.find(inCustody.id()).orElseThrow().status()).isEqualTo(ParcelStatus.PICKUP_CONFIRMED);
        assertThat(f.parcels.find(notYet.id()).orElseThrow().status()).isEqualTo(ParcelStatus.CANCELLED);
    }

    @Test
    void nothingInTheCustodyChainReachesDelivered() {
        ParcelService.Parcel p = accepted();
        f.parcels.confirmPickup(driver, p.id());
        f.parcels.dispatch(driver, p.id());
        f.parcels.arrive(driver, p.id());

        // DELIVERED даёт только код получателя (SP09-02); здесь такого перехода нет.
        assertThat(f.parcels.find(p.id()).orElseThrow().status()).isNotEqualTo(ParcelStatus.DELIVERED);
        assertThat(jdbc.sql("SELECT count(*) FROM parcel_requests WHERE status = 'DELIVERED'").query(Long.class).single()).isZero();
    }
}
