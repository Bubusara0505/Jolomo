package kg.jolomo.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kg.jolomo.bookings.BookingService;
import kg.jolomo.parcels.ParcelService;
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

/** Заготовка тестов метрик и выгрузки: настоящие сервисы поездок, броней и посылок на PostGIS. */
final class PilotFixture {

    static final UUID ADMIN = UUID.randomUUID();

    final MutableClock clock = new MutableClock();
    final JdbcClient jdbc;
    final DomainEventStore events;
    final BookingService bookings;
    final ParcelService parcels;
    final TripService trips;
    final DriverProfileService profiles;
    final UUID bishkekPoint;
    private int phoneSeq = 0;

    PilotFixture(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = jdbc;
        AuditLog audit = new AuditLog(jdbc, clock);
        profiles = new DriverProfileService(jdbc, tx, clock, audit);
        ObligationConfigService obligations = new ObligationConfigService(jdbc, tx, clock, audit);
        EligibilityService eligibility = new EligibilityService(jdbc, profiles, obligations);
        events = new DomainEventStore(jdbc, tx, clock);
        IdempotencyService idempotency = new IdempotencyService(jdbc, tx, clock);
        bookings = new BookingService(jdbc, tx, clock, events, eligibility, idempotency);
        parcels = new ParcelService(jdbc, tx, clock, events, obligations, eligibility, audit, idempotency);
        trips = new TripService(jdbc, tx, clock, eligibility, profiles, events, List.of(bookings, parcels));
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        obligations.load(ObligationConfigService.PARCEL_CREATE, "leg-v1", ADMIN);
        bishkekPoint = jdbc.sql("SELECT id FROM pickup_locations WHERE code = 'BISHKEK_WEST_BUS_STATION'")
                .query(UUID.class)
                .single();
    }

    UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", String.format("+99655500%04d", ++phoneSeq))
                .update();
        return id;
    }

    String phoneOf(UUID user) {
        return jdbc.sql("SELECT phone FROM users WHERE id = :id").param("id", user).query(String.class).single();
    }

    UUID driver() {
        UUID id = user();
        profiles.create(id);
        profiles.transition(id, DriverProfileStatus.SUBMITTED, id, "SELF_SERVICE");
        profiles.transition(id, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
        profiles.transition(id, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK");
        return id;
    }

    /** Опубликованная поездка через сутки: seats мест и parcelSlots слотов посылок. */
    UUID publishedTrip(UUID driver, int seats, int parcelSlots) {
        Instant from = clock.instant().plus(Duration.ofDays(1));
        TripService.Trip draft = trips.createDraft(driver, new TripService.TripDraft(
                Direction.BISHKEK_TO_KARAKOL, bishkekPoint, from, from.plus(Duration.ofHours(2)), seats, 700, parcelSlots));
        return trips.publish(driver, draft.id()).id();
    }

    ParcelService.NewParcel parcelFor(UUID trip) {
        return new ParcelService.NewParcel(trip, "S", "DOCUMENTS", "+996700000001");
    }
}
