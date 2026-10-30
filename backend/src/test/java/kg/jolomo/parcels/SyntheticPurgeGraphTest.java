package kg.jolomo.parcels;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.users.ActivityFlagService;
import kg.jolomo.users.SyntheticDataService;
import kg.jolomo.verification.IdentityFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SP04-03 на живых данных: водитель, отправитель, поездка, посылка, проверка доставки, инцидент, пометка и файл личности.
 * Удаляется вся связанная группа синтетических пользователей. Если по ту сторону сделки реальный человек, не удаляется никто из них.
 */
class SyntheticPurgeGraphTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    @TempDir
    Path dir;

    private DeliveryFixture d;
    private AuditLog audit;
    private IdentityFileStore store;
    private SyntheticDataService synthetic;

    @BeforeEach
    void setUp() {
        d = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);
        audit = new AuditLog(jdbc, d.f.clock);
        store = new IdentityFileStore(dir, TEST_SMS_KEY);
        synthetic = new SyntheticDataService(jdbc, tx, d.f.clock, audit, store::deleteQuietly);
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private boolean exists(UUID user) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id)").param("id", user).query(Boolean.class).single();
    }

    private void markExpired(UUID... users) {
        for (UUID u : users) {
            synthetic.markSynthetic(ADMIN, u, d.f.clock.instant().plus(Duration.ofHours(1)));
        }
    }

    /** Файл личности и заявка у водителя. */
    private UUID identityFileOf(UUID driver) {
        UUID caseId = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        store.write(object, new byte[] {1, 2, 3});
        jdbc.sql("INSERT INTO verification_cases (id, driver_user_id, status, created_at) VALUES (:c, :u, 'SUBMITTED', now())")
                .param("c", caseId)
                .param("u", driver)
                .update();
        jdbc.sql("INSERT INTO identity_objects (id, owner_user_id, case_id, storage_key, content_type, size_bytes, sha256, state, created_at) "
                        + "VALUES (:o, :u, :c, :k, 'image/png', 3, 'x', 'STORED', now())")
                .param("o", object)
                .param("u", driver)
                .param("c", caseId)
                .param("k", IdentityFileStore.keyFor(object))
                .update();
        return object;
    }

    @Test
    void aWholeSyntheticDealIsRemovedWithItsEvidenceFilesAndAppendOnlyRecords() {
        DeliveryFixture.Pending p = d.pending();
        d.issue(p); // активная проверка доставки
        IncidentService incidents = new IncidentService(jdbc, tx, d.f.clock, d.f.events, audit);
        incidents.record(ADMIN, p.parcel(), "PARCEL_DAMAGED", "RECIPIENT_CALLED");
        new ActivityFlagService(jdbc, tx, d.f.clock, audit).record(ADMIN, p.sender(), "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED");
        UUID object = identityFileOf(p.driver());
        int eventsBefore = count("SELECT count(*) FROM domain_events");
        markExpired(p.sender(), p.driver());
        d.f.clock.advance(Duration.ofHours(2));

        var result = synthetic.purgeExpired();

        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.skippedLinked()).isZero();
        for (String table : new String[] {"parcel_requests", "trips", "delivery_challenges", "incidents", "suspicious_activity",
                "identity_objects", "verification_cases", "driver_profiles"}) {
            assertThat(count("SELECT count(*) FROM " + table)).as(table).isZero();
        }
        assertThat(exists(p.sender())).isFalse();
        assertThat(exists(p.driver())).isFalse();
        assertThat(store.exists(object)).isFalse();
        // журналы событий и аудит остаются
        assertThat(count("SELECT count(*) FROM domain_events")).isEqualTo(eventsBefore);
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'SYNTHETIC_USER_PURGED'")).isEqualTo(2);
    }

    @Test
    void aSyntheticDriverWhoseDealHasARealCounterpartyIsLeftAlone() {
        DeliveryFixture.Pending p = d.pending(); // отправитель реальный: не помечен
        markExpired(p.driver());
        d.f.clock.advance(Duration.ofHours(2));

        var result = synthetic.purgeExpired();

        assertThat(result.deleted()).isZero();
        assertThat(result.skippedLinked()).isEqualTo(1);
        assertThat(exists(p.driver())).isTrue();
        assertThat(count("SELECT count(*) FROM parcel_requests")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM trips")).isEqualTo(1);
    }

    @Test
    void aHeldCounterpartyKeepsTheWholeDealEvenIfTheOtherSideIsSyntheticAndExpired() {
        DeliveryFixture.Pending p = d.pending();
        markExpired(p.sender(), p.driver());
        new kg.jolomo.users.LegalHoldService(jdbc, tx, d.f.clock, audit).place(ADMIN, p.sender(), "LEGAL_REQUEST_1");
        d.f.clock.advance(Duration.ofHours(2));

        var result = synthetic.purgeExpired();

        assertThat(result.deleted()).isZero();
        assertThat(result.skippedLegalHold()).isEqualTo(1);
        assertThat(result.skippedLinked()).isEqualTo(1);
        assertThat(exists(p.sender())).isTrue();
        assertThat(exists(p.driver())).isTrue();
        assertThat(count("SELECT count(*) FROM parcel_requests")).isEqualTo(1);
    }

    @Test
    void aSyntheticPassengerBookingOnASyntheticTripGoesAndOnARealTripBlocksTheDeletion() {
        UUID driver = d.f.driver();
        UUID trip = d.f.publishedTrip(driver, 1);
        UUID passenger = d.f.user();
        jdbc.sql("INSERT INTO bookings (id, trip_id, passenger_user_id, seats, price_kgs, status, created_at, updated_at) "
                        + "VALUES (:id, :t, :p, 1, 700, 'REQUESTED', now(), now())")
                .param("id", UUID.randomUUID())
                .param("t", trip)
                .param("p", passenger)
                .update();
        markExpired(passenger);
        d.f.clock.advance(Duration.ofHours(2));

        // водитель реальный: бронь пассажира удалять нельзя, потому что она часть чужой поездки
        assertThat(synthetic.purgeExpired().deleted()).isZero();
        assertThat(count("SELECT count(*) FROM bookings")).isEqualTo(1);

        // оба синтетические: уходят вместе с поездкой, бронью и профилем водителя
        markExpired(driver);
        d.f.clock.advance(Duration.ofHours(2));
        assertThat(synthetic.purgeExpired().deleted()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM bookings")).isZero();
        assertThat(count("SELECT count(*) FROM trips")).isZero();
    }
}
