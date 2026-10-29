package kg.jolomo.parcels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.shared.IdempotencyService;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.trips.Direction;
import kg.jolomo.trips.TripService;
import kg.jolomo.verification.DriverProfileService;
import kg.jolomo.verification.DriverProfileStatus;
import kg.jolomo.verification.EligibilityService;
import kg.jolomo.verification.ObligationConfigService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** Заготовка тестов посылок: сервисы на настоящем PostGIS. Обязательства для поездок загружены, для посылок нет. */
final class ParcelFixture {

    static final UUID ADMIN = UUID.randomUUID();

    final MutableClock clock = new MutableClock();
    final JdbcClient jdbc;
    final TransactionOperations tx;
    final ObligationConfigService obligations;
    final DomainEventStore events;
    final IdempotencyService idempotency;
    final ParcelService parcels;
    final TripService trips;
    final DriverProfileService profiles;
    final UUID bishkekPoint;
    private int phoneSeq = 0;

    ParcelFixture(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.tx = tx;
        AuditLog audit = new AuditLog(jdbc, clock);
        profiles = new DriverProfileService(jdbc, tx, clock, audit);
        obligations = new ObligationConfigService(jdbc, tx, clock, audit);
        EligibilityService eligibility = new EligibilityService(jdbc, profiles, obligations);
        events = new DomainEventStore(jdbc, tx, clock);
        idempotency = new IdempotencyService(jdbc, tx, clock);
        parcels = new ParcelService(jdbc, tx, clock, events, obligations, idempotency);
        trips = new TripService(jdbc, tx, clock, eligibility, profiles, events, List.of(parcels));
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        bishkekPoint = jdbc.sql("SELECT id FROM pickup_locations WHERE code = 'BISHKEK_WEST_BUS_STATION'")
                .query(UUID.class)
                .single();
    }

    void loadParcelObligations() {
        obligations.load(ObligationConfigService.PARCEL_CREATE, "leg-v1", ADMIN);
    }

    UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", String.format("+99655500%04d", ++phoneSeq))
                .update();
        return id;
    }

    UUID driver() {
        UUID id = user();
        profiles.create(id);
        profiles.transition(id, DriverProfileStatus.SUBMITTED, id, "SELF_SERVICE");
        profiles.transition(id, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
        profiles.transition(id, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK");
        return id;
    }

    /** Опубликованная поездка завтра: 4 места и parcelSlots слотов посылок. */
    UUID publishedTrip(UUID driver, int parcelSlots) {
        Instant from = clock.instant().plus(Duration.ofDays(1));
        TripService.Trip draft = trips.createDraft(driver, new TripService.TripDraft(
                Direction.BISHKEK_TO_KARAKOL, bishkekPoint, from, from.plus(Duration.ofHours(2)), 4, 700, parcelSlots));
        return trips.publish(driver, draft.id()).id();
    }

    String phoneOf(UUID user) {
        return jdbc.sql("SELECT phone FROM users WHERE id = :id").param("id", user).query(String.class).single();
    }

    /** Правильная заявка: свободный номер получателя, который не принадлежит ни отправителю, ни водителю. */
    ParcelService.NewParcel valid(UUID trip) {
        return new ParcelService.NewParcel(trip, "S", "DOCUMENTS", "+996700000001");
    }

    int parcelSlotsAvailable(UUID trip) {
        return jdbc.sql("SELECT parcel_slots_available FROM trips WHERE id = :id").param("id", trip)
                .query(Integer.class).single();
    }

    long parcelCount() {
        return jdbc.sql("SELECT count(*) FROM parcel_requests").query(Long.class).single();
    }

    static void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
