package kg.jolomo.parcels;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import kg.jolomo.events.DomainEventStore;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Инциденты поддержки (SP09-01). Поддержка записывает случай по посылке, и только это: сервис не трогает ни статус
 * посылки, ни слоты, ни коды. Доставленной посылку отсюда сделать нельзя, спор (DISPUTED) не открывается. В запись
 * сохраняется версия условий посылки. Свободного текста нет, поэтому персональных данных в инциденте не бывает.
 * Право администратора (INCIDENT_RECORD) проверяет контроллер.
 */
@Service
public class IncidentService {

    public record Incident(UUID id, UUID parcelId, String kind, String reasonCode, UUID recordedBy,
                           String parcelStatus, String termsVersion, Instant createdAt) {}

    private static final Pattern REASON = Pattern.compile("^[A-Z0-9_]{3,64}$");
    private static final String SELECT = """
            SELECT id, parcel_id, kind, reason_code, recorded_by, parcel_status, terms_version, created_at FROM incidents""";

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final DomainEventStore events;
    private final AuditLog audit;

    public IncidentService(JdbcClient jdbc, TransactionOperations tx, Clock clock, DomainEventStore events, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
    }

    public Incident record(UUID adminId, UUID parcelId, String kind, String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "error.reason.required");
        }
        if (parcelId == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PARCEL_NOT_FOUND", "error.parcel.not_found");
        }
        if (kind == null || !kindActive(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INCIDENT_KIND", "error.incident.invalid_kind");
        }
        return tx.execute(status -> {
            var parcel = jdbc.sql("SELECT status, obligation_version FROM parcel_requests WHERE id = :id")
                    .param("id", parcelId)
                    .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)})
                    .optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PARCEL_NOT_FOUND", "error.parcel.not_found"));
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO incidents (id, parcel_id, kind, reason_code, recorded_by, parcel_status, terms_version, created_at)
                            VALUES (:id, :p, :kind, :reason, :admin, :ps, :tv, :now)
                            """)
                    .param("id", id)
                    .param("p", parcelId)
                    .param("kind", kind)
                    .param("reason", reasonCode)
                    .param("admin", adminId)
                    .param("ps", parcel[0])
                    .param("tv", parcel[1])
                    .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .update();
            // Состояние до и после одно и то же: инцидент посылку не меняет.
            audit.record(adminId, "INCIDENT_RECORDED", "INCIDENT", id, reasonCode, parcel[0], parcel[0]);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("incidentId", id);
            payload.put("kind", kind);
            payload.put("parcelStatus", parcel[0]);
            payload.put("termsVersion", parcel[1]);
            events.append("PARCEL", parcelId, "PARCEL_INCIDENT_RECORDED", adminId, payload);
            return find(id).orElseThrow();
        });
    }

    public Optional<Incident> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> incident(rs)).optional();
    }

    public List<Incident> list(UUID parcelId, int limit) {
        return jdbc.sql(SELECT + " WHERE parcel_id = :p ORDER BY created_at DESC, id LIMIT :limit")
                .param("p", parcelId)
                .param("limit", Math.max(1, Math.min(limit, 100)))
                .query((rs, i) -> incident(rs))
                .list();
    }

    private boolean kindActive(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM incident_kinds WHERE code = :c AND active)")
                .param("c", code)
                .query(Boolean.class)
                .single();
    }

    private static Incident incident(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Incident(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class).toInstant());
    }
}
