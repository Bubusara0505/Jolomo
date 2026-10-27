package kg.jolomo.bookings;

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

/** Общая заготовка тестов броней: сервисы на настоящем PostGIS, водители, пассажиры и опубликованные поездки. */
final class BookingFixture {

    private static final UUID ADMIN = UUID.randomUUID();

    final MutableClock clock = new MutableClock();
    final JdbcClient jdbc;
    final TransactionOperations tx;
    final DriverProfileService profiles;
    final EligibilityService eligibility;
    final DomainEventStore events;
    final IdempotencyService idempotency;
    final BookingService bookings;
    final TripService trips;
    final UUID bishkekPoint;
    private int phoneSeq = 0;

    BookingFixture(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.tx = tx;
        AuditLog audit = new AuditLog(jdbc, clock);
        profiles = new DriverProfileService(jdbc, tx, clock, audit);
        ObligationConfigService obligations = new ObligationConfigService(jdbc, tx, clock, audit);
        eligibility = new EligibilityService(jdbc, profiles, obligations);
        events = new DomainEventStore(jdbc, tx, clock);
        idempotency = new IdempotencyService(jdbc, tx, clock);
        bookings = new BookingService(jdbc, tx, clock, events, eligibility, idempotency);
        trips = new TripService(jdbc, tx, clock, eligibility, profiles, events, List.of(bookings));
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        bishkekPoint = jdbc.sql("SELECT id FROM pickup_locations WHERE code = 'BISHKEK_WEST_BUS_STATION'")
                .query(UUID.class)
                .single();
    }

    /** Те же зависимости, новые объекты: имитация рестарта приложения при той же базе. */
    BookingService restartedBookings() {
        IdempotencyService freshIdempotency = new IdempotencyService(jdbc, tx, clock);
        return new BookingService(jdbc, tx, clock, new DomainEventStore(jdbc, tx, clock), eligibility, freshIdempotency);
    }

    UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", String.format("+99655500%04d", ++phoneSeq))
                .update();
        return id;
    }

    /** Пользователь с одобренным профилем водителя. */
    UUID driver() {
        UUID id = user();
        profiles.create(id);
        profiles.transition(id, DriverProfileStatus.SUBMITTED, id, "SELF_SERVICE");
        profiles.transition(id, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
        profiles.transition(id, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK");
        return id;
    }

    /** Опубликованная поездка завтра на seats мест по 700 сомов. */
    UUID publishedTrip(UUID driver, int seats) {
        Instant from = clock.instant().plus(Duration.ofDays(1));
        TripService.Trip draft = trips.createDraft(driver, new TripService.TripDraft(
                Direction.BISHKEK_TO_KARAKOL, bishkekPoint, from, from.plus(Duration.ofHours(2)), seats, 700, 0));
        return trips.publish(driver, draft.id()).id();
    }

    int seatsAvailable(UUID trip) {
        return jdbc.sql("SELECT seats_available FROM trips WHERE id = :id").param("id", trip).query(Integer.class).single();
    }

    long bookingCount() {
        return jdbc.sql("SELECT count(*) FROM bookings").query(Long.class).single();
    }

    static void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
