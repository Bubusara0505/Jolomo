package kg.jolomo.trips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.trips.TripService.TripDraft;
import kg.jolomo.verification.DriverProfileService;
import kg.jolomo.verification.DriverProfileStatus;
import kg.jolomo.verification.EligibilityService;
import kg.jolomo.verification.ObligationConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP04-04: проверка черновика и публикации. Публикация не проходит без допуска и без загруженных обязательств. */
class TripServiceTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    private final MutableClock clock = new MutableClock();
    private DriverProfileService profiles;
    private ObligationConfigService obligations;
    private TripService trips;
    private UUID driver;
    private UUID bishkekPoint;
    private UUID karakolPoint;

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(jdbc, clock);
        profiles = new DriverProfileService(jdbc, tx, clock, audit);
        obligations = new ObligationConfigService(jdbc, tx, clock, audit);
        trips = new TripService(jdbc, tx, clock, new EligibilityService(jdbc, profiles, obligations), profiles,
                new kg.jolomo.events.DomainEventStore(jdbc, tx, clock));
        driver = newUser("+996555000060");
        profiles.create(driver);
        bishkekPoint = point("BISHKEK_WEST_BUS_STATION");
        karakolPoint = point("KARAKOL_BUS_STATION");
    }

    private UUID newUser(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", phone)
                .update();
        return id;
    }

    private UUID point(String code) {
        return jdbc.sql("SELECT id FROM pickup_locations WHERE code = :c").param("c", code).query(UUID.class).single();
    }

    private void approve(UUID user) {
        profiles.transition(user, DriverProfileStatus.SUBMITTED, user, "SELF_SERVICE");
        profiles.transition(user, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
        profiles.transition(user, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK");
    }

    private TripDraft good() {
        Instant from = clock.instant().plus(Duration.ofDays(1));
        return new TripDraft(Direction.BISHKEK_TO_KARAKOL, bishkekPoint, from, from.plus(Duration.ofHours(2)), 4, 700, 2);
    }

    private TripDraft with(TripDraft d, Direction dir, UUID pickup, Instant from, Instant to, int seats, int price, int parcels) {
        return new TripDraft(dir, pickup, from, to, seats, price, parcels);
    }

    private void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void validDraftIsStoredWithFullCapacityAndNotPublished() {
        var t = trips.createDraft(driver, good());

        assertThat(t.status()).isEqualTo(TripStatus.DRAFT);
        assertThat(t.seatsAvailable()).isEqualTo(4);
        assertThat(t.priceKgs()).isEqualTo(700);
        assertThat(t.ownerId()).isEqualTo(driver);
    }

    @Test
    void draftValidationCoversEveryRequiredField() {
        TripDraft g = good();
        Instant from = g.departureFrom();
        Instant to = g.departureTo();

        expectCode(() -> trips.createDraft(driver, with(g, null, bishkekPoint, from, to, 4, 700, 0)), "INVALID_DIRECTION");
        expectCode(() -> trips.createDraft(driver, with(g, g.direction(), bishkekPoint, to, from, 4, 700, 0)), "INVALID_WINDOW");
        expectCode(() -> trips.createDraft(driver, with(g, g.direction(), bishkekPoint, from, from, 4, 700, 0)), "INVALID_WINDOW");
        expectCode(() -> trips.createDraft(driver, with(g, g.direction(), bishkekPoint, clock.instant().minusSeconds(60),
                clock.instant().plusSeconds(3600), 4, 700, 0)), "DEPARTURE_IN_PAST");
        expectCode(() -> trips.createDraft(driver, with(g, g.direction(), bishkekPoint, from, to, 0, 700, 0)), "INVALID_SEATS");
        expectCode(() -> trips.createDraft(driver, with(g, g.direction(), bishkekPoint, from, to, 4, 0, 0)), "INVALID_PRICE");
        expectCode(() -> trips.createDraft(driver, with(g, g.direction(), bishkekPoint, from, to, 4, 700, -1)), "INVALID_PARCEL_SLOTS");
        expectCode(() -> trips.createDraft(driver, with(g, g.direction(), null, from, to, 4, 700, 0)), "INVALID_REQUEST");

        assertThat(jdbc.sql("SELECT count(*) FROM trips").query(Integer.class).single()).isZero();
    }

    @Test
    void pickupMustBeInTheCatalogActiveAndInTheOriginCity() {
        TripDraft g = good();

        expectCode(() -> trips.createDraft(driver, with(g, g.direction(), UUID.randomUUID(), g.departureFrom(), g.departureTo(), 4, 700, 0)),
                "PICKUP_NOT_IN_CATALOG");
        expectCode(() -> trips.createDraft(driver, with(g, Direction.BISHKEK_TO_KARAKOL, karakolPoint, g.departureFrom(), g.departureTo(), 4, 700, 0)),
                "PICKUP_WRONG_CITY");
        expectCode(() -> trips.createDraft(driver, with(g, Direction.KARAKOL_TO_BISHKEK, bishkekPoint, g.departureFrom(), g.departureTo(), 4, 700, 0)),
                "PICKUP_WRONG_CITY");

        jdbc.sql("UPDATE pickup_locations SET active = false WHERE id = :id").param("id", bishkekPoint).update();
        expectCode(() -> trips.createDraft(driver, g), "PICKUP_NOT_IN_CATALOG");
    }

    @Test
    void reverseDirectionWorksFromKarakol() {
        TripDraft g = good();

        var t = trips.createDraft(driver, with(g, Direction.KARAKOL_TO_BISHKEK, karakolPoint, g.departureFrom(), g.departureTo(), 3, 650, 0));

        assertThat(t.direction()).isEqualTo(Direction.KARAKOL_TO_BISHKEK);
    }

    @Test
    void userWithoutDriverProfileCannotCreateTrips() {
        UUID passenger = newUser("+996555000061");

        expectCode(() -> trips.createDraft(passenger, good()), "NOT_A_DRIVER");
    }

    @Test
    void unverifiedDriverCannotPublish() {
        var t = trips.createDraft(driver, good());
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);

        expectCode(() -> trips.publish(driver, t.id()), "NOT_APPROVED");
        assertThat(trips.find(t.id()).orElseThrow().status()).isEqualTo(TripStatus.DRAFT);
    }

    @Test
    void approvedDriverCannotPublishWhileObligationsAreOff() {
        approve(driver);
        var t = trips.createDraft(driver, good());

        expectCode(() -> trips.publish(driver, t.id()), "OBLIGATIONS_NOT_LOADED");
        assertThat(trips.find(t.id()).orElseThrow().status()).isEqualTo(TripStatus.DRAFT);
    }

    @Test
    void approvedDriverWithLoadedObligationsPublishes() {
        approve(driver);
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        var t = trips.createDraft(driver, good());

        var published = trips.publish(driver, t.id());

        assertThat(published.status()).isEqualTo(TripStatus.PUBLISHED);
        assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM trips WHERE id = :id").param("id", t.id()).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void suspendedDriverCannotPublishEvenWithAnOldDraft() {
        approve(driver);
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        var t = trips.createDraft(driver, good());
        profiles.suspend(driver, ADMIN, "SUSPICIOUS_ACTIVITY");

        expectCode(() -> trips.publish(driver, t.id()), "NOT_APPROVED");
    }

    @Test
    void onlyTheOwnerCanPublishAndTheTripIsNotRevealedToOthers() {
        approve(driver);
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        var t = trips.createDraft(driver, good());
        UUID other = newUser("+996555000062");
        profiles.create(other);
        approve(other);

        expectCode(() -> trips.publish(other, t.id()), "TRIP_NOT_FOUND");
        expectCode(() -> trips.publish(driver, UUID.randomUUID()), "TRIP_NOT_FOUND");
    }

    @Test
    void secondPublishIsRefusedAndDepartureMustStillBeInTheFuture() {
        approve(driver);
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        var t = trips.createDraft(driver, good());
        trips.publish(driver, t.id());

        expectCode(() -> trips.publish(driver, t.id()), "INVALID_TRIP_STATE");

        var late = trips.createDraft(driver, good());
        clock.advance(Duration.ofDays(2));
        expectCode(() -> trips.publish(driver, late.id()), "DEPARTURE_IN_PAST");
    }
}
