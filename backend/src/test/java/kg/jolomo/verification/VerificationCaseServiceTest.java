package kg.jolomo.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.testsupport.Samples;
import kg.jolomo.verification.VerificationCaseService.FilePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SP03-04: закрытая загрузка. Отказ не оставляет файлов и строк, но оставляет аудит. */
class VerificationCaseServiceTest extends PostgisTestBase {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock();
    private DriverProfileService profiles;
    private IdentityFileStore store;
    private VerificationCaseService service;
    private UUID driver;

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(jdbc, clock);
        profiles = new DriverProfileService(jdbc, tx, clock, audit);
        store = new IdentityFileStore(dir, TEST_SMS_KEY);
        service = new VerificationCaseService(jdbc, tx, clock, audit, new UploadValidator(), store, profiles);
        driver = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000030', 'ACTIVE', now())")
                .param("id", driver)
                .update();
        profiles.create(driver);
    }

    private static FilePart part(String type, byte[] bytes) {
        return new FilePart(type, bytes.length, bytes);
    }

    private long filesOnDisk() throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private void assertNothingStoredAndAudited(String reason) throws Exception {
        assertThat(filesOnDisk()).isZero();
        assertThat(count("SELECT count(*) FROM verification_cases")).isZero();
        assertThat(count("SELECT count(*) FROM identity_objects")).isZero();
        assertThat(profiles.find(driver).orElseThrow().status()).isEqualTo(DriverProfileStatus.UNVERIFIED);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'VERIFICATION_UPLOAD_REJECTED' AND reason_code = :r")
                        .param("r", reason)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void validSubmissionIsStoredEncryptedAndMovesProfileToSubmitted() throws Exception {
        byte[] png = Samples.png(20, 20);
        byte[] pdf = Samples.pdf();

        var result = service.submit(driver, List.of(part("image/png", png), part("application/pdf", pdf)));

        assertThat(result.files()).isEqualTo(2);
        assertThat(filesOnDisk()).isEqualTo(2);
        assertThat(profiles.find(driver).orElseThrow().status()).isEqualTo(DriverProfileStatus.SUBMITTED);
        assertThat(count("SELECT count(*) FROM identity_objects WHERE state = 'STORED'")).isEqualTo(2);
        UUID first = jdbc.sql("SELECT id FROM identity_objects WHERE content_type = 'image/png'")
                .query(UUID.class)
                .single();
        assertThat(store.read(first)).isEqualTo(png);
        assertThat(service.latest(driver).orElseThrow().status()).isEqualTo("SUBMITTED");
    }

    @Test
    void disguisedExecutableLeavesNothingButAnAuditRow() throws Exception {
        assertThatThrownBy(() -> service.submit(driver, List.of(part("image/png", Samples.fakeExe()))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("UPLOAD_TYPE_NOT_ALLOWED"));

        assertNothingStoredAndAudited("TYPE_NOT_ALLOWED");
    }

    @Test
    void oneBadFileAmongGoodOnesStoresNothing() throws Exception {
        assertThatThrownBy(() -> service.submit(driver, List.of(
                        part("image/png", Samples.png(10, 10)),
                        part("application/pdf", Samples.pdfWith("/JavaScript 1 0 R")))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("UPLOAD_ACTIVE_CONTENT"));

        assertNothingStoredAndAudited("ACTIVE_CONTENT");
    }

    @Test
    void oversizedFileIsRejectedWith413() throws Exception {
        assertThatThrownBy(() -> service.submit(driver, List.of(new FilePart("image/png", UploadValidator.MAX_BYTES + 1, null))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("UPLOAD_TOO_LARGE");
                    assertThat(e.status().value()).isEqualTo(413);
                });

        assertNothingStoredAndAudited("TOO_LARGE");
    }

    @Test
    void secondSubmissionWhileOpenIsRejectedWithoutNewFiles() throws Exception {
        service.submit(driver, List.of(part("image/png", Samples.png(10, 10))));
        long before = filesOnDisk();

        assertThatThrownBy(() -> service.submit(driver, List.of(part("image/png", Samples.png(10, 10)))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CASE_NOT_ALLOWED"));

        assertThat(filesOnDisk()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM verification_cases")).isEqualTo(1);
    }

    @Test
    void userWithoutDriverProfileCannotSubmit() throws Exception {
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000031', 'ACTIVE', now())")
                .param("id", other)
                .update();

        assertThatThrownBy(() -> service.submit(other, List.of(part("image/png", Samples.png(10, 10)))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DRIVER_PROFILE_NOT_FOUND"));

        assertThat(filesOnDisk()).isZero();
    }

    @Test
    void failureAfterFilesWereWrittenRemovesThem() throws Exception {
        // Открытая заявка уже есть (в обход сервиса): вставка новой нарушит уникальность внутри транзакции.
        jdbc.sql("INSERT INTO verification_cases (id, driver_user_id, status, created_at) VALUES (:id, :u, 'SUBMITTED', now())")
                .param("id", UUID.randomUUID())
                .param("u", driver)
                .update();

        assertThatThrownBy(() -> service.submit(driver, List.of(part("image/png", Samples.png(10, 10)))))
                .isInstanceOf(RuntimeException.class);

        assertThat(filesOnDisk()).isZero();
        assertThat(count("SELECT count(*) FROM identity_objects")).isZero();
    }

    @Test
    void rejectedDriverMayResubmit() throws Exception {
        service.submit(driver, List.of(part("image/png", Samples.png(10, 10))));
        profiles.transition(driver, DriverProfileStatus.IN_REVIEW, UUID.randomUUID(), "REVIEW_STARTED");
        profiles.transition(driver, DriverProfileStatus.REJECTED, UUID.randomUUID(), "DOCS_UNCLEAR");
        jdbc.sql("UPDATE verification_cases SET status = 'REJECTED'").update();

        service.submit(driver, List.of(part("image/jpeg", Samples.jpeg(10, 10))));

        assertThat(profiles.find(driver).orElseThrow().status()).isEqualTo(DriverProfileStatus.SUBMITTED);
        assertThat(count("SELECT count(*) FROM verification_cases")).isEqualTo(2);
    }
}
