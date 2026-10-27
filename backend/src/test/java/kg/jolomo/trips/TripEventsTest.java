package kg.jolomo.trips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore;
import kg.jolomo.events.DomainEventStore.StoredEvent;
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

/** SP05-04: жизненный цикл поездки пишет события без дублей, с верными идентификаторами и без лишних данных. */
class TripEventsTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    private final MutableClock clock = new MutableClock();
    private DriverProfileService profiles;
    private ObligationConfigService obligations;
    private DomainEventStore events;
    private TripService trips;
    private UUID driver;
    private UUID other;
    private UUID bishkekPoint;

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(jdbc, clock);
        profiles = new DriverProfileService(jdbc, tx, clock, audit);
        obligations = new ObligationConfigService(jdbc, tx, clock, audit);
        events = new DomainEventStore(jdbc, tx, clock);
        trips = new TripService(jdbc, tx, clock, new EligibilityService(jdbc, profiles, obligations), profiles, events);
        driver = driver("+996555000090", true);
        other = driver("+996555000091", true);
        bishkekPoint = jdbc.sql("SELECT id FROM pickup_locations WHERE code = 'BISHKEK_WEST_BUS_STATION'").query(UUID.class).single();
    }

    private UUID driver(String phone, boolean approved) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", phone)
                .update();
        profiles.create(id);
        if (approved) {
            profiles.transition(id, DriverProfileStatus.SUBMITTED, id, "SELF_SERVICE");
            profiles.transition(id, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
            profiles.transition(id, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK");
        }
        return id;
    }

    private TripDraft good() {
        Instant from = clock.instant().plus(Duration.ofDays(1));
        return new TripDraft(Direction.BISHKEK_TO_KARAKOL, bishkekPoint, from, from.plus(Duration.ofHours(2)), 4, 700, 2);
    }

    private TripPatch price(int p) {
        return new TripPatch(null, null, null, null, null, p, null);
    }

    private List<StoredEvent> log(UUID tripId) {
        return events.forAggregate("TRIP", tripId);
    }

    private String field(UUID tripId, int version, String expression) {
        return jdbc.sql("SELECT " + expression + " FROM domain_events WHERE aggregate_id = :a AND version = :v")
                .param("a", tripId)
                .param("v", version)
                .query(String.class)
                .single();
    }

    private long totalEvents() {
        return jdbc.sql("SELECT count(*) FROM domain_events").query(Long.class).single();
    }

    @Test
    void fullLifecycleWritesOneEventPerChangeWithConsecutiveVersions() {
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        var t = trips.createDraft(driver, good());
        trips.update(driver, t.id(), price(800));
        trips.publish(driver, t.id());
        trips.update(driver, t.id(), new TripPatch(null, null, null, null, 6, null, null));
        trips.cancel(driver, t.id());

        List<StoredEvent> log = log(t.id());

        assertThat(log).extracting(StoredEvent::eventType)
                .containsExactly("TRIP_CREATED", "TRIP_UPDATED", "TRIP_PUBLISHED", "TRIP_UPDATED", "TRIP_CANCELLED");
        assertThat(log).extracting(StoredEvent::version).containsExactly(1, 2, 3, 4, 5);
        assertThat(log).allSatisfy(e -> {
            assertThat(e.aggregateType()).isEqualTo("TRIP");
            assertThat(e.aggregateId()).isEqualTo(t.id());
            assertThat(e.actorUserId()).isEqualTo(driver);
            assertThat(e.occurredAt()).isEqualTo(clock.instant());
        });
        assertThat(log.stream().map(StoredEvent::id).distinct().count()).isEqualTo(5);
    }

    @Test
    void payloadsDescribeTheChangeAndCarryNoPersonalData() {
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        var t = trips.createDraft(driver, good());
        trips.update(driver, t.id(), price(800));
        trips.publish(driver, t.id());
        trips.cancel(driver, t.id());

        assertThat(field(t.id(), 1, "payload->>'direction'")).isEqualTo("BISHKEK_TO_KARAKOL");
        assertThat(field(t.id(), 1, "payload->>'priceKgs'")).isEqualTo("700");
        assertThat(field(t.id(), 1, "payload->>'status'")).isEqualTo("DRAFT");
        assertThat(field(t.id(), 2, "payload->'changes'->'priceKgs'->>'from'")).isEqualTo("700");
        assertThat(field(t.id(), 2, "payload->'changes'->'priceKgs'->>'to'")).isEqualTo("800");
        assertThat(field(t.id(), 3, "payload->>'from'")).isEqualTo("DRAFT");
        assertThat(field(t.id(), 3, "payload->>'to'")).isEqualTo("PUBLISHED");
        assertThat(field(t.id(), 4, "payload->>'from'")).isEqualTo("PUBLISHED");
        assertThat(field(t.id(), 4, "payload->>'to'")).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT string_agg(payload::text, ' ') FROM domain_events").query(String.class).single())
                .doesNotContain("+996");
    }

    @Test
    void repeatedCancelAndNoOpEditWriteNothingNew() {
        var t = trips.createDraft(driver, good());
        trips.cancel(driver, t.id());
        long afterCancel = totalEvents();

        trips.cancel(driver, t.id());
        assertThat(totalEvents()).isEqualTo(afterCancel);

        var t2 = trips.createDraft(driver, good());
        long before = totalEvents();
        trips.update(driver, t2.id(), price(700)); // та же цена
        assertThat(totalEvents()).isEqualTo(before);
        assertThat(log(t2.id())).hasSize(1);
    }

    @Test
    void refusedOperationsLeaveNoEvents() {
        var t = trips.createDraft(driver, good());
        long before = totalEvents();

        // нет допуска к публикации (выключатель обязательств не загружен)
        assertThatThrownBy(() -> trips.publish(driver, t.id())).isInstanceOf(ApiException.class);
        // чужая поездка
        assertThatThrownBy(() -> trips.update(other, t.id(), price(1))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> trips.cancel(other, t.id())).isInstanceOf(ApiException.class);
        // невалидная правка и невалидное создание
        assertThatThrownBy(() -> trips.update(driver, t.id(), price(0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> trips.createDraft(driver, new TripDraft(null, null, null, null, 0, 0, 0))).isInstanceOf(ApiException.class);

        assertThat(totalEvents()).isEqualTo(before);
        assertThat(log(t.id())).extracting(StoredEvent::eventType).containsExactly("TRIP_CREATED");
    }

    @Test
    void tripsOfDifferentDriversHaveSeparateLogs() {
        var a = trips.createDraft(driver, good());
        var b = trips.createDraft(other, good());
        trips.update(driver, a.id(), price(900));

        assertThat(log(a.id())).hasSize(2);
        assertThat(log(b.id())).hasSize(1);
        assertThat(log(b.id()).get(0).actorUserId()).isEqualTo(other);
    }
}
