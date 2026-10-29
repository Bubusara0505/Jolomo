package kg.jolomo.parcels;

import static kg.jolomo.parcels.ParcelFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore.StoredEvent;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP08-01: принятие закрепляет один слот посылки, отмена возвращает его не более одного раза, переходы закрыты. */
class ParcelDecisionTest extends PostgisTestBase {

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

    private ParcelService.Parcel requested() {
        return f.parcels.create(f.user(), f.valid(trip));
    }

    @Test
    void acceptTakesExactlyOneSlotWhateverTheSizeAndRepeatedAcceptTakesNoMore() {
        ParcelService.Parcel big = f.parcels.create(f.user(), new ParcelService.NewParcel(trip, "L", "DOCUMENTS", "+996700000001"));

        assertThat(f.parcels.accept(driver, big.id()).status()).isEqualTo(ParcelStatus.ACCEPTED);
        assertThat(f.parcels.accept(driver, big.id()).status()).isEqualTo(ParcelStatus.ACCEPTED);

        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(2);
        assertThat(f.events.forAggregate("PARCEL", big.id())).extracting(StoredEvent::eventType)
                .containsExactly("PARCEL_REQUESTED", "PARCEL_ACCEPTED");
    }

    @Test
    void theLastSlotGoesToOneParcelAndTheOtherStaysRequested() {
        UUID small = f.publishedTrip(driver, 1);
        ParcelService.Parcel first = f.parcels.create(f.user(), f.valid(small));
        ParcelService.Parcel second = f.parcels.create(f.user(), f.valid(small));
        f.parcels.accept(driver, first.id());

        expectCode(() -> f.parcels.accept(driver, second.id()), "NO_PARCEL_CAPACITY");

        assertThat(f.parcels.find(second.id()).orElseThrow().status()).isEqualTo(ParcelStatus.REQUESTED);
        assertThat(f.parcelSlotsAvailable(small)).isZero();
    }

    @Test
    void rejectChangesNoSlotsAndRepeatedRejectIsSafe() {
        ParcelService.Parcel p = requested();

        assertThat(f.parcels.reject(driver, p.id()).status()).isEqualTo(ParcelStatus.REJECTED);
        assertThat(f.parcels.reject(driver, p.id()).status()).isEqualTo(ParcelStatus.REJECTED);

        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(3);
    }

    @Test
    void cancellingAnAcceptedParcelFreesTheSlotExactlyOnce() {
        ParcelService.Parcel p = requested();
        f.parcels.accept(driver, p.id());

        for (int i = 0; i < 3; i++) {
            assertThat(f.parcels.cancel(p.senderId(), p.id()).status()).isEqualTo(ParcelStatus.CANCELLED);
        }

        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(3);
        assertThat(f.events.forAggregate("PARCEL", p.id())).extracting(StoredEvent::eventType)
                .containsExactly("PARCEL_REQUESTED", "PARCEL_ACCEPTED", "PARCEL_CANCELLED");
    }

    @Test
    void cancellingARequestedParcelFreesNothing() {
        ParcelService.Parcel p = requested();

        f.parcels.cancel(p.senderId(), p.id());

        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(3);
    }

    @Test
    void driverCanCancelAnAcceptedParcelButNotARequestedOne() {
        ParcelService.Parcel accepted = requested();
        ParcelService.Parcel pending = requested();
        f.parcels.accept(driver, accepted.id());

        assertThat(f.parcels.cancel(driver, accepted.id()).status()).isEqualTo(ParcelStatus.CANCELLED);
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(3);
        expectCode(() -> f.parcels.cancel(driver, pending.id()), "INVALID_PARCEL_STATE");
    }

    @Test
    void forbiddenTransitionsAreBlocked() {
        ParcelService.Parcel rejected = requested();
        f.parcels.reject(driver, rejected.id());
        ParcelService.Parcel accepted = requested();
        f.parcels.accept(driver, accepted.id());
        ParcelService.Parcel cancelled = requested();
        f.parcels.cancel(cancelled.senderId(), cancelled.id());

        expectCode(() -> f.parcels.accept(driver, rejected.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.accept(driver, cancelled.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.reject(driver, accepted.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.reject(driver, cancelled.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.cancel(rejected.senderId(), rejected.id()), "INVALID_PARCEL_STATE");
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(2);
    }

    @Test
    void onceTheParcelIsWithTheDriverItCannotBeCancelledOrAcceptedAgain() {
        ParcelService.Parcel p = requested();
        f.parcels.accept(driver, p.id());
        // Дальше состояния ставит SP08-02; здесь имитируем его прямой правкой.
        jdbc.sql("UPDATE parcel_requests SET status = 'PICKUP_CONFIRMED' WHERE id = :id").param("id", p.id()).update();

        expectCode(() -> f.parcels.cancel(p.senderId(), p.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.cancel(driver, p.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.accept(driver, p.id()), "INVALID_PARCEL_STATE");
        expectCode(() -> f.parcels.reject(driver, p.id()), "INVALID_PARCEL_STATE");
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(2);
    }

    @Test
    void onlyTheDriverOfTheTripDecidesAndStrangersGetNotFound() {
        ParcelService.Parcel p = requested();
        UUID otherDriver = f.driver();
        UUID stranger = f.user();

        expectCode(() -> f.parcels.accept(otherDriver, p.id()), "PARCEL_NOT_FOUND");
        expectCode(() -> f.parcels.reject(otherDriver, p.id()), "PARCEL_NOT_FOUND");
        expectCode(() -> f.parcels.accept(p.senderId(), p.id()), "PARCEL_NOT_FOUND");
        expectCode(() -> f.parcels.cancel(stranger, p.id()), "PARCEL_NOT_FOUND");
        expectCode(() -> f.parcels.accept(driver, UUID.randomUUID()), "PARCEL_NOT_FOUND");
        assertThat(f.parcels.find(p.id()).orElseThrow().status()).isEqualTo(ParcelStatus.REQUESTED);
    }

    @Test
    void anUnapprovedDriverCannotAcceptButCanStillRejectAndCancel() {
        ParcelService.Parcel toAccept = requested();
        ParcelService.Parcel toReject = requested();
        ParcelService.Parcel accepted = requested();
        f.parcels.accept(driver, accepted.id());
        jdbc.sql("UPDATE driver_profiles SET status = 'SUSPENDED' WHERE user_id = :u").param("u", driver).update();

        expectCode(() -> f.parcels.accept(driver, toAccept.id()), "NOT_APPROVED");
        assertThat(f.parcels.reject(driver, toReject.id()).status()).isEqualTo(ParcelStatus.REJECTED);
        assertThat(f.parcels.cancel(driver, accepted.id()).status()).isEqualTo(ParcelStatus.CANCELLED);
    }

    @Test
    void whenTheObligationsAreSwitchedOffAcceptIsRefusedButRejectAndCancelStillWork() {
        ParcelService.Parcel toAccept = requested();
        ParcelService.Parcel toReject = requested();
        f.obligations.disable("PARCEL_CREATE", ParcelFixture.ADMIN, "LEGAL_REVIEW");

        expectCode(() -> f.parcels.accept(driver, toAccept.id()), "OBLIGATIONS_NOT_LOADED");
        assertThat(f.parcels.reject(driver, toReject.id()).status()).isEqualTo(ParcelStatus.REJECTED);
        assertThat(f.parcels.cancel(toAccept.senderId(), toAccept.id()).status()).isEqualTo(ParcelStatus.CANCELLED);
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(3);
    }

    @Test
    void afterDepartureNothingCanBeAcceptedOrCancelled() {
        ParcelService.Parcel pending = requested();
        ParcelService.Parcel accepted = requested();
        f.parcels.accept(driver, accepted.id());
        f.clock.advance(Duration.ofDays(2));

        expectCode(() -> f.parcels.accept(driver, pending.id()), "TRIP_DEPARTED");
        expectCode(() -> f.parcels.cancel(accepted.senderId(), accepted.id()), "TRIP_DEPARTED");
        assertThat(f.parcels.find(accepted.id()).orElseThrow().status()).isEqualTo(ParcelStatus.ACCEPTED);
    }

    @Test
    void tripCapacityCannotBeEditedBelowAcceptedParcels() {
        ParcelService.Parcel p = requested();
        f.parcels.accept(driver, p.id());

        expectCode(() -> f.trips.update(driver, trip,
                new kg.jolomo.trips.TripService.TripPatch(null, null, null, null, null, null, 0)), "CAPACITY_BELOW_TAKEN");
        f.trips.update(driver, trip, new kg.jolomo.trips.TripService.TripPatch(null, null, null, null, null, null, 5));
        assertThat(f.parcelSlotsAvailable(trip)).isEqualTo(4);
    }

    @Test
    void databaseNeverLetsSlotsGoBelowZeroOrAboveTotal() {
        for (String sql : new String[] {
                "UPDATE trips SET parcel_slots_available = -1 WHERE id = :id",
                "UPDATE trips SET parcel_slots_available = parcel_slots_total + 1 WHERE id = :id"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(sql).param("id", trip).update())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
