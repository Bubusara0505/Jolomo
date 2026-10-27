package kg.jolomo.verification;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Очередь проверки и решение по заявке (SP04-02). Права администратора проверяет контроллер (AdminAuth);
 * здесь только правила состояний. Каждый шаг: код причины, строка аудита, перевод профиля водителя.
 * В очереди нет телефонов и байтов документов.
 */
@Service
public class VerificationReviewService {

    public record QueueItem(UUID caseId, UUID driverId, String status, Instant createdAt, int files) {}

    public record FileInfo(UUID id, String contentType, long sizeBytes) {}

    public record CaseDetail(UUID caseId, UUID driverId, String status, Instant createdAt, List<FileInfo> files) {}

    private static final Pattern REASON = Pattern.compile("^[A-Z0-9_]{3,64}$");

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;
    private final DriverProfileService profiles;

    VerificationReviewService(
            JdbcClient jdbc, TransactionOperations tx, Clock clock, AuditLog audit, DriverProfileService profiles) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
        this.profiles = profiles;
    }

    /** Открытые заявки, старые первыми. status: SUBMITTED, IN_REVIEW или null (обе). */
    public List<QueueItem> queue(String status, int limit) {
        if (status != null && !status.equals("SUBMITTED") && !status.equals("IN_REVIEW")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "error.case.invalidStatus");
        }
        int capped = Math.max(1, Math.min(limit, 100));
        return jdbc.sql("""
                        SELECT c.id, c.driver_user_id, c.status, c.created_at,
                               (SELECT count(*) FROM identity_objects o WHERE o.case_id = c.id AND o.state = 'STORED')
                        FROM verification_cases c
                        WHERE c.status IN ('SUBMITTED', 'IN_REVIEW') AND (:status::text IS NULL OR c.status = :status::text)
                        ORDER BY c.created_at ASC
                        LIMIT :limit
                        """)
                .param("status", status)
                .param("limit", capped)
                .query((rs, i) -> new QueueItem(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getInt(5)))
                .list();
    }

    public CaseDetail detail(UUID caseId) {
        var head = jdbc.sql("SELECT driver_user_id, status, created_at FROM verification_cases WHERE id = :id")
                .param("id", caseId)
                .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, OffsetDateTime.class).toInstant()})
                .optional()
                .orElseThrow(this::notFound);
        List<FileInfo> files = jdbc.sql("""
                        SELECT id, content_type, size_bytes FROM identity_objects
                        WHERE case_id = :c AND state = 'STORED' ORDER BY created_at, id
                        """)
                .param("c", caseId)
                .query((rs, i) -> new FileInfo(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)))
                .list();
        return new CaseDetail(caseId, (UUID) head[0], (String) head[1], (Instant) head[2], files);
    }

    public void startReview(UUID adminId, UUID caseId, String reasonCode) {
        requireReason(reasonCode);
        tx.executeWithoutResult(status -> {
            Locked c = lock(caseId);
            if (!"SUBMITTED".equals(c.status())) {
                throw invalidState();
            }
            setStatus(caseId, "IN_REVIEW", adminId, null);
            profiles.transition(c.driverId(), DriverProfileStatus.IN_REVIEW, adminId, reasonCode);
            audit.record(adminId, "VERIFICATION_REVIEW_STARTED", "VERIFICATION_CASE", caseId, reasonCode, "SUBMITTED", "IN_REVIEW");
        });
    }

    public void decide(UUID adminId, UUID caseId, boolean approve, String reasonCode) {
        requireReason(reasonCode);
        tx.executeWithoutResult(status -> {
            Locked c = lock(caseId);
            if (!"IN_REVIEW".equals(c.status())) {
                throw invalidState();
            }
            String to = approve ? "APPROVED" : "REJECTED";
            setStatus(caseId, to, adminId, reasonCode);
            profiles.transition(c.driverId(), approve ? DriverProfileStatus.APPROVED : DriverProfileStatus.REJECTED, adminId, reasonCode);
            audit.record(adminId, approve ? "VERIFICATION_APPROVED" : "VERIFICATION_REJECTED", "VERIFICATION_CASE", caseId,
                    reasonCode, "IN_REVIEW", to);
        });
    }

    private Locked lock(UUID caseId) {
        return jdbc.sql("SELECT driver_user_id, status FROM verification_cases WHERE id = :id FOR UPDATE")
                .param("id", caseId)
                .query((rs, i) -> new Locked(rs.getObject(1, UUID.class), rs.getString(2)))
                .optional()
                .orElseThrow(this::notFound);
    }

    private void setStatus(UUID caseId, String status, UUID reviewer, String reason) {
        jdbc.sql("UPDATE verification_cases SET status = :s, reviewer_id = :r, reason_code = :c, updated_at = :now WHERE id = :id")
                .param("s", status)
                .param("r", reviewer)
                .param("c", reason)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("id", caseId)
                .update();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND", "error.case.notFound");
    }

    private static ApiException invalidState() {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_CASE_STATE", "error.case.invalidState");
    }

    private static void requireReason(String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "error.reason.required");
        }
    }

    private record Locked(UUID driverId, String status) {}
}
