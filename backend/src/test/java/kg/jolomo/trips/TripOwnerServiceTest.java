package kg.jolomo.trips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.trips.TripService.TripDraft;
import kg.jolomo.trips.TripService.TripPatch;
import kg.jolomo.verification.DriverProfileService;
import kg.jolomo.verification.DriverProfileStatus;
import kg.jolomo.verification.EligibilityService;
import kg.jolomo.verification.ObligationConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP05-01: матрица «кто и в каком состоянии что может» для правки, публикации и отмены. */
class TripOwnerServiceTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    private final MutableClock clock = new MutableClock();
    private DriverProfileService profiles;
    private TripService trips;
    private UUID driver;
    private UUID other;
    private UUID bishkekPoint;
    private UUID karakolPoint;

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(jdbc, clock);
        profiles = new DriverProfileService(jdbc, tx, clock, audit);
        ObligationConfigService obligations = new ObligationConfigService(jdbc, tx, clock, audit);
        trips = new TripService(jdbc, tx, clock, new EligibilityService(jdbc, profiles, obligations), profiles);
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        driver = driver("+996555000070");
        other = driver("+996555000071");
        bishkekPoint = point("BISHKEK_WEST_BUS_STATION");
        karakolPoint = point("KARAKOL_BUS_STATION");
    }

    /** Пользователь с одобренным профилем водителя. */
    private UUID driver(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", phone)
                .update();
        profiles.create(id);
        profiles.transition(id, DriverProfileStatus.SUBMITTED, id, "SELF_SERVICE");
        profiles.transition(id, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
        profiles.transition(id, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK");
        return id;
    }

    private UUID point(String code) {
        return jdbc.sql("SELECT id FROM pickup_locations WHERE code = :c").param("c", code).query(UUID.class).single();
    }

    private TripDraft good() {
        Instant from = clock.instant().plus(Duration.ofDays(1));
        return new TripDraft(Direction.BISHKEK_TO_KARAKOL, bishkekPoint, from, from.plus(Duration.ofHours(2)), 4, 700, 2);
    }

    private TripPatch price(int p) {
        return new TripPatch(null, null, null, null, null, p, null);
    }

    private void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private TripService.Trip published() {
        var t = trips.createDraft(driver, good());
        return trips.publish(driver, t.id());
    }

    private Instant updatedAt(UUID id) {
        return jdbc.sql("SELECT updated_at FROM trips WHERE id = :id").param("id", id)
                .query((rs, i) -> rs.getObject(1, OffsetDateTime.class).toInstant()).single();
    }

    // ---- правка черновика ----

    @Test
    void ownerEditsEveryFieldOfADraftAndCapacityIsReset() {
        var t = trips.createDraft(driver, good());
        Instant from = clock.instant().plus(Duration.ofDays(3));

        var edited = trips.update(driver, t.id(), new TripPatch(Direction.KARAKOL_TO_BISHKEK, karakolPoint, from,
                from.plus(Duration.ofHours(3)), 6, 900, 1));

        assertThat(edited.direction()).isEqualTo(Direction.KARAKOL_TO_BISHKEK);
        assertThat(edited.pickupLocationId()).isEqualTo(karakolPoint);
        assertThat(edited.seatsTotal()).isEqualTo(6);
        assertThat(edited.seatsAvailable()).isEqualTo(6);
        assertThat(edited.priceKgs()).isEqualTo(900);
        assertThat(edited.parcelSlotsTotal()).isEqualTo(1);
        assertThat(edited.parcelSlotsAvailable()).isEqualTo(1);
        assertThat(edited.status()).isEqualTo(TripStatus.DRAFT);
    }

    @Test
    void draftEditIsValidatedAsAWholeAndNothingIsSavedOnFailure() {
        var t = trips.createDraft(driver, good());

        // Новое направление без смены точки: точка осталась в Бишкеке.
        expectCode(() -> trips.update(driver, t.id(),
                new TripPatch(Direction.KARAKOL_TO_BISHKEK, null, null, null, null, null, null)), "PICKUP_WRONG_CITY");
        expectCode(() -> trips.update(driver, t.id(), price(0)), "INVALID_PRICE");
        expectCode(() -> trips.update(driver, t.id(), new TripPatch(null, null, null, null, 0, null, null)), "INVALID_SEATS");
        expectCode(() -> trips.update(driver, t.id(),
                new TripPatch(null, null, clock.instant().minusSeconds(5), null, null, null, null)), "DEPARTURE_IN_PAST");
        expectCode(() -> trips.update(driver, t.id(), new TripPatch(null, null, null, null, null, null, null)), "INVALID_REQUEST");

        var after = trips.find(t.id()).orElseThrow();
        assertThat(after.direction()).isEqualTo(Direction.BISHKEK_TO_KARAKOL);
        assertThat(after.priceKgs()).isEqualTo(700);
    }

    // ---- матрица владения ----

    @Test
    void anotherDriverSeesNothingAndChangesNothing() {
        var t = published();

        expectCode(() -> trips.update(other, t.id(), price(1)), "TRIP_NOT_FOUND");
        expectCode(() -> trips.cancel(other, t.id()), "TRIP_NOT_FOUND");
        expectCode(() -> trips.cancel(driver, UUID.randomUUID()), "TRIP_NOT_FOUND");
        assertThat(trips.find(t.id()).orElseThrow().status()).isEqualTo(TripStatus.PUBLISHED);
        assertThat(trips.find(t.id()).orElseThrow().priceKgs()).isEqualTo(700);
    }

    // ---- правка опубликованной ----

    @Test
    void publishedTripAllowsPriceAndCapacityButNotRouteOrTime() {
        var t = published();

        assertThat(trips.update(driver, t.id(), price(800)).priceKgs()).isEqualTo(800);
        var bigger = trips.update(driver, t.id(), new TripPatch(null, null, null, null, 6, null, 3));
        assertThat(bigger.seatsTotal()).isEqualTo(6);
        assertThat(bigger.seatsAvailable()).isEqualTo(6);

        Instant later = clock.instant().plus(Duration.ofDays(5));
        expectCode(() -> trips.update(driver, t.id(), new TripPatch(null, null, later, later.plusSeconds(3600), null, null, null)),
                "TRIP_FIELD_LOCKED");
        expectCode(() -> trips.update(driver, t.id(), new TripPatch(null, karakolPoint, null, null, null, null, null)),
                "TRIP_FIELD_LOCKED");
        assertThat(trips.find(t.id()).orElseThrow().status()).isEqualTo(TripStatus.PUBLISHED);
    }

    @Test
    void capacityCannotDropBelowWhatIsAlreadyTakenAndAvailabilityKeepsTheTakenSeats() {
        var t = published();
        // Занято 3 места из 4 (когда появятся брони, это сделают они).
        jdbc.sql("UPDATE trips SET seats_available = 1 WHERE id = :id").param("id", t.id()).update();

        expectCode(() -> trips.update(driver, t.id(), new TripPatch(null, null, null, null, 2, null, null)), "CAPACITY_BELOW_TAKEN");

        var grown = trips.update(driver, t.id(), new TripPatch(null, null, null, null, 5, null, null));
        assertThat(grown.seatsTotal()).isEqualTo(5);
        assertThat(grown.seatsAvailable()).isEqualTo(2);
    }

    @Test
    void driverWhoLostEligibilityCannotEditAPublishedTripButCanCancelIt() {
        var t = published();
        profiles.suspend(driver, ADMIN, "SUSPICIOUS_ACTIVITY");

        expectCode(() -> trips.update(driver, t.id(), price(100)), "NOT_APPROVED");
        assertThat(trips.cancel(driver, t.id()).status()).isEqualTo(TripStatus.CANCELLED);
    }

    // ---- отмена ----

    @Test
    void cancelWorksForDraftAndPublishedAndRepeatIsHarmless() {
        var d = trips.createDraft(driver, good());
        assertThat(trips.cancel(driver, d.id()).status()).isEqualTo(TripStatus.CANCELLED);

        var p = published();
        assertThat(trips.cancel(driver, p.id()).status()).isEqualTo(TripStatus.CANCELLED);
        Instant stamp = updatedAt(p.id());
        clock.advance(Duration.ofMinutes(5));

        assertThat(trips.cancel(driver, p.id()).status()).isEqualTo(TripStatus.CANCELLED);
        assertThat(updatedAt(p.id())).isEqualTo(stamp);
    }

    @Test
    void cancelledAndCompletedTripsAreFrozen() {
        var t = published();
        trips.cancel(driver, t.id());

        expectCode(() -> trips.update(driver, t.id(), price(100)), "INVALID_TRIP_STATE");
        expectCode(() -> trips.publish(driver, t.id()), "INVALID_TRIP_STATE");

        var done = published();
        jdbc.sql("UPDATE trips SET status = 'COMPLETED' WHERE id = :id").param("id", done.id()).update();
        expectCode(() -> trips.cancel(driver, done.id()), "INVALID_TRIP_STATE");
        expectCode(() -> trips.update(driver, done.id(), price(100)), "INVALID_TRIP_STATE");
    }

    // ---- чтение ----

    @Test
    void ownListHoldsOnlyOwnTripsNewestFirst() {
        var a = trips.createDraft(driver, good());
        Instant later = clock.instant().plus(Duration.ofDays(4));
        var b = trips.createDraft(driver, new TripDraft(Direction.BISHKEK_TO_KARAKOL, bishkekPoint, later,
                later.plusSeconds(3600), 3, 600, 0));
        trips.createDraft(other, good());

        assertThat(trips.listOwn(driver)).extracting(TripService.Trip::id).containsExactly(b.id(), a.id());
    }

    @Test
    void publicViewShowsOnlyPublishedUpcomingTripsWithoutOwnerData() {
        var draft = trips.createDraft(driver, good());
        assertThat(trips.findPublic(draft.id())).isEmpty();

        var t = trips.publish(driver, draft.id());
        var pub = trips.findPublic(t.id()).orElseThrow();
        assertThat(pub.pickupNameRu()).isEqualTo("Западный автовокзал Бишкека");
        assertThat(pub.priceKgs()).isEqualTo(700);
        assertThat(pub.currency()).isEqualTo("KGS");
        assertThat(pub.seatsAvailable()).isEqualTo(4);
        assertThat(pub.toString()).doesNotContain(driver.toString());

        trips.cancel(driver, t.id());
        assertThat(trips.findPublic(t.id())).isEmpty();

        var again = trips.publish(driver, trips.createDraft(driver, good()).id());
        clock.advance(Duration.ofDays(2));
        assertThat(trips.findPublic(again.id())).isEmpty();
    }
}
