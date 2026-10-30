package kg.jolomo.parcels;

import static kg.jolomo.parcels.ParcelFixture.expectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** SP09-01: поддержка записывает инцидент и ничем больше. Посылка не меняется, доставки и спора нет. */
class IncidentServiceTest extends PostgisTestBase {

    private static final UUID SUPPORT = UUID.randomUUID();

    private DeliveryFixture d;
    private IncidentService incidents;

    @BeforeEach
    void setUp() {
        d = new DeliveryFixture(jdbc, tx, TEST_SMS_KEY);
        incidents = new IncidentService(jdbc, tx, d.f.clock, d.f.events, new AuditLog(jdbc, d.f.clock));
    }

    private String parcelRow(UUID parcel) {
        return jdbc.sql("SELECT status || '|' || updated_at::text FROM parcel_requests WHERE id = :id")
                .param("id", parcel)
                .query(String.class)
                .single();
    }

    @Test
    void recordingAnIncidentDoesNotTouchTheParcelAndNeverDeliversOrDisputes() {
        DeliveryFixture.Pending p = d.pending();
        String before = parcelRow(p.parcel());
        d.f.clock.advance(Duration.ofMinutes(5));

        incidents.record(SUPPORT, p.parcel(), "CODE_NOT_RECEIVED", "RECIPIENT_CALLED");

        assertThat(parcelRow(p.parcel())).isEqualTo(before); // тот же статус и то же updated_at
        assertThat(d.count("SELECT count(*) FROM parcel_requests WHERE status IN ('DELIVERED', 'DISPUTED')")).isZero();
        assertThat(d.count("SELECT count(*) FROM domain_events WHERE event_type IN ('PARCEL_DELIVERED')")).isZero();
        assertThat(d.count("SELECT count(*) FROM delivery_challenges WHERE status = 'CONSUMED'")).isZero();
    }

    @Test
    void worksInEveryStateAndLeavesEachStateAsItWas() {
        DeliveryFixture.Pending transit = d.inTransit();

        DeliveryFixture.Pending locked = d.pending();
        String[] ch = d.issue(locked);
        for (int i = 0; i < 3; i++) {
            d.delivery.verify(UUID.fromString(ch[0]), DeliveryFixture.wrongThan(ch[1]));
        }

        DeliveryFixture.Pending delivered = d.pending();
        String[] ok = d.issue(delivered);
        d.delivery.verify(UUID.fromString(ok[0]), ok[1]);

        for (DeliveryFixture.Pending p : new DeliveryFixture.Pending[] {transit, locked, delivered}) {
            String before = parcelRow(p.parcel());
            incidents.record(SUPPORT, p.parcel(), "PARCEL_DAMAGED", "RECIPIENT_CALLED");
            assertThat(parcelRow(p.parcel())).isEqualTo(before);
        }
        assertThat(d.parcelStatus(transit.parcel())).isEqualTo("IN_TRANSIT");
        assertThat(d.parcelStatus(locked.parcel())).isEqualTo("VERIFICATION_LOCKED");
        assertThat(d.parcelStatus(delivered.parcel())).isEqualTo("DELIVERED");
        assertThat(d.count("SELECT count(*) FROM parcel_requests WHERE status = 'DISPUTED'")).isZero();
    }

    @Test
    void theIncidentKeepsTheTermsVersionAndTheParcelStateAtThatMoment() {
        DeliveryFixture.Pending p = d.pending();

        IncidentService.Incident i = incidents.record(SUPPORT, p.parcel(), "HANDOVER_DISPUTE", "RECIPIENT_CALLED");

        assertThat(i.termsVersion()).isEqualTo("leg-v1"); // версия набора обязательств, при которой создана посылка
        assertThat(i.parcelStatus()).isEqualTo("DELIVERY_PENDING");
        assertThat(i.recordedBy()).isEqualTo(SUPPORT);
        assertThat(i.kind()).isEqualTo("HANDOVER_DISPUTE");
        assertThat(incidents.find(i.id())).contains(i);
    }

    @Test
    void auditAndDomainEventAreWrittenWithoutPersonalData() {
        DeliveryFixture.Pending p = d.pending();

        IncidentService.Incident i = incidents.record(SUPPORT, p.parcel(), "PARCEL_LOST", "RECIPIENT_CALLED");

        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'INCIDENT_RECORDED' AND actor_user_id IS NOT NULL "
                + "AND reason_code = 'RECIPIENT_CALLED' AND from_state = to_state")).isEqualTo(1);
        String payload = jdbc.sql("SELECT payload::text FROM domain_events WHERE event_type = 'PARCEL_INCIDENT_RECORDED'")
                .query(String.class)
                .single();
        assertThat(payload).contains(i.id().toString()).contains("PARCEL_LOST").doesNotContain("+996");
    }

    @Test
    void inputIsValidatedAndNothingIsWrittenOnRefusal() {
        DeliveryFixture.Pending p = d.pending();

        expectCode(() -> incidents.record(SUPPORT, p.parcel(), "PARCEL_LOST", null), "REASON_REQUIRED");
        expectCode(() -> incidents.record(SUPPORT, p.parcel(), "PARCEL_LOST", "free text"), "REASON_REQUIRED");
        expectCode(() -> incidents.record(SUPPORT, p.parcel(), "NO_SUCH_KIND", "RECIPIENT_CALLED"), "INVALID_INCIDENT_KIND");
        expectCode(() -> incidents.record(SUPPORT, p.parcel(), null, "RECIPIENT_CALLED"), "INVALID_INCIDENT_KIND");
        expectCode(() -> incidents.record(SUPPORT, UUID.randomUUID(), "PARCEL_LOST", "RECIPIENT_CALLED"), "PARCEL_NOT_FOUND");
        jdbc.sql("UPDATE incident_kinds SET active = false WHERE code = 'PARCEL_LOST'").update();
        expectCode(() -> incidents.record(SUPPORT, p.parcel(), "PARCEL_LOST", "RECIPIENT_CALLED"), "INVALID_INCIDENT_KIND");

        assertThat(d.count("SELECT count(*) FROM incidents")).isZero();
        assertThat(d.count("SELECT count(*) FROM audit_events WHERE action = 'INCIDENT_RECORDED'")).isZero();
    }

    @Test
    void incidentsAndAuditRowsCannotBeChangedOrDeleted() {
        DeliveryFixture.Pending p = d.pending();
        incidents.record(SUPPORT, p.parcel(), "PARCEL_LOST", "RECIPIENT_CALLED");

        assertThatThrownBy(() -> jdbc.sql("UPDATE incidents SET kind = 'OTHER'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM incidents").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_events SET reason_code = 'X'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_events").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void listShowsNewestFirstForOneParcelOnly() {
        DeliveryFixture.Pending a = d.pending();
        DeliveryFixture.Pending b = d.pending();
        incidents.record(SUPPORT, a.parcel(), "PARCEL_LOST", "RECIPIENT_CALLED");
        d.f.clock.advance(Duration.ofMinutes(1));
        incidents.record(SUPPORT, a.parcel(), "OTHER", "RECIPIENT_CALLED");
        incidents.record(SUPPORT, b.parcel(), "OTHER", "RECIPIENT_CALLED");

        var list = incidents.list(a.parcel(), 50);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).kind()).isEqualTo("OTHER");
        assertThat(list.get(1).kind()).isEqualTo("PARCEL_LOST");
    }
}
