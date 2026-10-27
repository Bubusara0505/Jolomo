package kg.jolomo.verification;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.Samples;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** Общая заготовка тестов разбора: водитель с поданной заявкой на синтетических файлах. */
final class ReviewFixture {

    final MutableClock clock = new MutableClock();
    final AuditLog audit;
    final DriverProfileService profiles;
    final IdentityFileStore store;
    final VerificationCaseService cases;
    final VerificationReviewService review;
    final IdentityAccessService access;
    private final JdbcClient jdbc;
    private int phoneSeq = 40;

    ReviewFixture(JdbcClient jdbc, TransactionOperations tx, Path dir, String key) {
        this.jdbc = jdbc;
        this.audit = new AuditLog(jdbc, clock);
        this.profiles = new DriverProfileService(jdbc, tx, clock, audit);
        this.store = new IdentityFileStore(dir, key);
        this.cases = new VerificationCaseService(jdbc, tx, clock, audit, new UploadValidator(), store, profiles);
        this.review = new VerificationReviewService(jdbc, tx, clock, audit, profiles);
        this.access = new IdentityAccessService(jdbc, tx, clock, audit, store);
    }

    /** Водитель с профилем и поданной заявкой из одного PNG и одного PDF. */
    Submitted submitted() {
        UUID driver = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", driver)
                .param("p", "+9965550000" + (phoneSeq++))
                .update();
        profiles.create(driver);
        var s = cases.submit(driver, List.of(
                new VerificationCaseService.FilePart("image/png", Samples.png(10, 10).length, Samples.png(10, 10)),
                new VerificationCaseService.FilePart("application/pdf", Samples.pdf().length, Samples.pdf())));
        List<UUID> objects = jdbc.sql("SELECT id FROM identity_objects WHERE case_id = :c ORDER BY content_type DESC")
                .param("c", s.caseId())
                .query(UUID.class)
                .list();
        return new Submitted(driver, s.caseId(), objects.get(0), objects.get(1));
    }

    record Submitted(UUID driverId, UUID caseId, UUID pngObject, UUID pdfObject) {}
}
