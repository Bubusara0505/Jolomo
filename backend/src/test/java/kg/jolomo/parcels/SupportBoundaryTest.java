package kg.jolomo.parcels;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.users.ActivityFlagService;
import org.junit.jupiter.api.Test;

/**
 * SP11-01: ни одно действие поддержки не завершает доставку и не добавляет вместимость. Прогоняем все действия
 * поддержки на живых данных и сверяем вместимость поездок и статусы посылок до и после.
 */
class SupportBoundaryTest extends PostgisTestBase {

    private static final UUID SUPPORT = UUID.randomUUID();

    private String capacity() {
        return jdbc.sql("""
                        SELECT coalesce(string_agg(id::text || ':' || seats_total || ':' || seats_available || ':'
                                                   || parcel_slots_total || ':' || parcel_slots_available, ',' ORDER BY id::text), '')
                        FROM trips
                        """)
                .query(String.class)
                .single();
    }

    private String statuses() {
        return jdbc.sql("SELECT coalesce(string_agg(id::text || ':' || status, ',' ORDER BY id::text), '') FROM parcel_requests")
                .query(String.class)
                .single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void supportActionsNeverDeliverAndNeverAddCapacity() {
        DeliveryFixture d = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);
        AuditLog audit = new AuditLog(jdbc, d.f.clock);
        IncidentService incidents = new IncidentService(jdbc, tx, d.f.clock, d.f.events, audit);
        ActivityFlagService flags = new ActivityFlagService(jdbc, tx, d.f.clock, audit);

        DeliveryFixture.Pending waiting = d.pending();
        DeliveryFixture.Pending transit = d.inTransit();
        DeliveryFixture.Pending locked = d.pending();
        String[] ch = d.issue(locked);
        for (int i = 0; i < 3; i++) {
            d.delivery.verify(UUID.fromString(ch[0]), DeliveryFixture.wrongThan(ch[1]));
        }
        assertThat(d.parcelStatus(locked.parcel())).isEqualTo("VERIFICATION_LOCKED");

        String capacityBefore = capacity();
        String statusesBefore = statuses();
        int deliveredBefore = count("SELECT count(*) FROM parcel_requests WHERE status = 'DELIVERED'");

        // Все действия поддержки.
        incidents.record(SUPPORT, waiting.parcel(), "CODE_NOT_RECEIVED", "RECIPIENT_CALLED");
        incidents.record(SUPPORT, transit.parcel(), "PARCEL_LOST", "RECIPIENT_CALLED");
        incidents.record(SUPPORT, locked.parcel(), "HANDOVER_DISPUTE", "RECIPIENT_CALLED");
        flags.record(SUPPORT, waiting.sender(), "FAKE_RECIPIENT_PHONE", "RECIPIENT_CALLED");
        d.f.profiles.suspend(waiting.driver(), SUPPORT, "SUSPICIOUS_ACTIVITY");
        d.f.profiles.liftSuspension(waiting.driver(), SUPPORT, "CLEARED");
        d.delivery.unlockVerification(SUPPORT, locked.parcel(), "RECIPIENT_CONFIRMED_BY_PHONE");

        assertThat(capacity()).isEqualTo(capacityBefore); // вместимость мест и слотов не выросла и не упала
        assertThat(count("SELECT count(*) FROM parcel_requests WHERE status = 'DELIVERED'")).isEqualTo(deliveredBefore);
        assertThat(count("SELECT count(*) FROM parcel_requests WHERE status = 'DISPUTED'")).isZero();
        assertThat(count("SELECT count(*) FROM delivery_challenges WHERE status = 'CONSUMED'")).isZero();
        // Единственное изменение статуса: снятая блокировка вернула посылку в ожидание получателя.
        assertThat(statuses()).isEqualTo(statusesBefore.replace(locked.parcel() + ":VERIFICATION_LOCKED", locked.parcel() + ":DELIVERY_PENDING"));
    }

    @Test
    void everySupportActionLeavesAnAuditRowWithActorAndReason() {
        DeliveryFixture d = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);
        AuditLog audit = new AuditLog(jdbc, d.f.clock);
        IncidentService incidents = new IncidentService(jdbc, tx, d.f.clock, d.f.events, audit);
        ActivityFlagService flags = new ActivityFlagService(jdbc, tx, d.f.clock, audit);
        DeliveryFixture.Pending p = d.pending();
        String[] ch = d.issue(p);
        for (int i = 0; i < 3; i++) {
            d.delivery.verify(UUID.fromString(ch[0]), DeliveryFixture.wrongThan(ch[1]));
        }

        incidents.record(SUPPORT, p.parcel(), "PARCEL_DAMAGED", "RECIPIENT_CALLED");
        flags.record(SUPPORT, p.sender(), "OTHER", "RECIPIENT_CALLED");
        d.f.profiles.suspend(p.driver(), SUPPORT, "SUSPICIOUS_ACTIVITY");
        d.f.profiles.liftSuspension(p.driver(), SUPPORT, "CLEARED");
        d.delivery.unlockVerification(SUPPORT, p.parcel(), "RECIPIENT_CONFIRMED_BY_PHONE");

        for (String action : new String[] {"INCIDENT_RECORDED", "SUSPICIOUS_ACTIVITY_FLAGGED", "DRIVER_SUSPENDED",
                "DRIVER_SUSPENSION_LIFTED", "PARCEL_VERIFICATION_UNLOCKED"}) {
            assertThat(count("SELECT count(*) FROM audit_events WHERE action = '" + action
                    + "' AND actor_user_id IS NOT NULL AND length(reason_code) >= 3")).as(action).isEqualTo(1);
        }
    }
}
