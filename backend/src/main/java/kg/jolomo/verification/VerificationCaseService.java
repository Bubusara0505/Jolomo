package kg.jolomo.verification;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Заявка на проверку водителя (SP03-04). Сначала проверяются все файлы, и только потом что-то пишется:
 * отказ не оставляет ни файлов, ни строк, а пишет в аудит причину. Файлы лежат в зашифрованном каталоге.
 * Файл, записанный до сбоя транзакции, удаляется.
 */
@Service
public class VerificationCaseService {

    public record Submitted(UUID caseId, int files) {}

    public record CaseView(UUID caseId, String status, Instant createdAt) {}

    public record FilePart(String declaredType, long size, byte[] bytes) {}

    private static final Set<DriverProfileStatus> MAY_SUBMIT =
            EnumSet.of(DriverProfileStatus.UNVERIFIED, DriverProfileStatus.REJECTED, DriverProfileStatus.EXPIRED);

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;
    private final UploadValidator validator;
    private final IdentityFileStore store;
    private final DriverProfileService profiles;

    VerificationCaseService(
            JdbcClient jdbc,
            TransactionOperations tx,
            Clock clock,
            AuditLog audit,
            UploadValidator validator,
            IdentityFileStore store,
            DriverProfileService profiles) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
        this.validator = validator;
        this.store = store;
        this.profiles = profiles;
    }

    public Submitted submit(UUID userId, List<FilePart> parts) {
        List<UploadValidator.Validated> files;
        try {
            files = validator.validateAll(parts == null ? null
                    : parts.stream().map(p -> new UploadValidator.Upload(p.declaredType(), p.size(), p.bytes())).toList());
        } catch (UploadRejectedException e) {
            // Вне транзакции: строка аудита остаётся, даже когда запрос закончился ошибкой.
            audit.record(userId, "VERIFICATION_UPLOAD_REJECTED", "VERIFICATION_CASE", null, e.reason(), null, null);
            throw e.toApi();
        }

        DriverProfileService.Profile profile = profiles.find(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DRIVER_PROFILE_NOT_FOUND", "error.driver.notFound"));
        if (!MAY_SUBMIT.contains(profile.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CASE_NOT_ALLOWED", "error.case.notAllowed");
        }

        List<UUID> written = new ArrayList<>();
        try {
            List<UUID> ids = new ArrayList<>();
            for (UploadValidator.Validated f : files) {
                UUID id = UUID.randomUUID();
                store.write(id, f.bytes());
                written.add(id);
                ids.add(id);
            }
            UUID caseId = UUID.randomUUID();
            tx.executeWithoutResult(status -> {
                OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
                jdbc.sql("INSERT INTO verification_cases (id, driver_user_id, status, created_at) VALUES (:id, :u, 'SUBMITTED', :now)")
                        .param("id", caseId)
                        .param("u", userId)
                        .param("now", now)
                        .update();
                for (int i = 0; i < files.size(); i++) {
                    UploadValidator.Validated f = files.get(i);
                    jdbc.sql("""
                                    INSERT INTO identity_objects
                                        (id, owner_user_id, case_id, storage_key, content_type, size_bytes, sha256, state, created_at)
                                    VALUES (:id, :u, :c, :key, :type, :size, :sha, 'STORED', :now)
                                    """)
                            .param("id", ids.get(i))
                            .param("u", userId)
                            .param("c", caseId)
                            .param("key", IdentityFileStore.keyFor(ids.get(i)))
                            .param("type", f.contentType())
                            .param("size", (long) f.bytes().length)
                            .param("sha", f.sha256())
                            .param("now", now)
                            .update();
                }
                profiles.transition(userId, DriverProfileStatus.SUBMITTED, userId, "SELF_SERVICE");
                audit.record(userId, "VERIFICATION_CASE_SUBMITTED", "VERIFICATION_CASE", caseId, "SELF_SERVICE", null, "SUBMITTED");
            });
            return new Submitted(caseId, files.size());
        } catch (RuntimeException e) {
            written.forEach(store::deleteQuietly);
            throw e;
        }
    }

    /** Последняя заявка водителя. Байтов документов здесь нет. */
    public Optional<CaseView> latest(UUID userId) {
        return jdbc.sql("""
                        SELECT id, status, created_at FROM verification_cases
                        WHERE driver_user_id = :u ORDER BY created_at DESC LIMIT 1
                        """)
                .param("u", userId)
                .query((rs, i) -> new CaseView(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, OffsetDateTime.class).toInstant()))
                .optional();
    }
}
