package kg.jolomo.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.testsupport.Samples;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SP04-02 и T-08: выдача документа на 5 минут, повторная проверка и аудит каждого просмотра. */
class IdentityAccessServiceTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OTHER_ADMIN = UUID.randomUUID();

    @TempDir
    Path dir;

    private ReviewFixture f;
    private ReviewFixture.Submitted s;

    @BeforeEach
    void setUp() {
        f = new ReviewFixture(jdbc, tx, dir, TEST_SMS_KEY);
        s = f.submitted();
    }

    private void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private int audit(String action, String reason) {
        return jdbc.sql("SELECT count(*) FROM audit_events WHERE action = :a AND reason_code = :r")
                .param("a", action)
                .param("r", reason)
                .query(Integer.class)
                .single();
    }

    @Test
    void grantThenDownloadReturnsTheOriginalBytesAndAuditsBothSteps() {
        var grant = f.access.grant(ADMIN, s.pngObject(), "IDENTITY_CHECK");

        var d = f.access.open(ADMIN, grant.token());

        assertThat(d.bytes()).isEqualTo(Samples.png(10, 10));
        assertThat(d.contentType()).isEqualTo("image/png");
        assertThat(grant.path()).isEqualTo(IdentityAccessService.PATH_PREFIX + grant.token());
        assertThat(audit("IDENTITY_ACCESS_GRANTED", "IDENTITY_CHECK")).isEqualTo(1);
        assertThat(audit("IDENTITY_FILE_VIEWED", "WITHIN_GRANT")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT actor_user_id FROM audit_events WHERE action = 'IDENTITY_FILE_VIEWED'")
                        .query(UUID.class).single())
                .isEqualTo(ADMIN);
    }

    @Test
    void tokenWorksForExactlyFiveMinutes() {
        var grant = f.access.grant(ADMIN, s.pdfObject(), "IDENTITY_CHECK");

        f.clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(f.access.open(ADMIN, grant.token()).bytes()).isEqualTo(Samples.pdf());

        f.clock.advance(Duration.ofSeconds(2));
        expectCode(() -> f.access.open(ADMIN, grant.token()), "ACCESS_EXPIRED");
        assertThat(audit("IDENTITY_ACCESS_DENIED", "EXPIRED")).isEqualTo(1);
        assertThat(grant.expiresAt()).isEqualTo(f.clock.instant().minusSeconds(1));
    }

    @Test
    void everyDownloadInsideTheWindowIsItsOwnAuditRow() {
        var grant = f.access.grant(ADMIN, s.pngObject(), "IDENTITY_CHECK");

        f.access.open(ADMIN, grant.token());
        f.access.open(ADMIN, grant.token());

        assertThat(audit("IDENTITY_FILE_VIEWED", "WITHIN_GRANT")).isEqualTo(2);
    }

    @Test
    void anotherAdminCannotUseSomeoneElsesToken() {
        var grant = f.access.grant(ADMIN, s.pngObject(), "IDENTITY_CHECK");

        expectCode(() -> f.access.open(OTHER_ADMIN, grant.token()), "ACCESS_NOT_FOUND");

        assertThat(audit("IDENTITY_ACCESS_DENIED", "WRONG_ADMIN")).isEqualTo(1);
        assertThat(audit("IDENTITY_FILE_VIEWED", "WITHIN_GRANT")).isZero();
    }

    @Test
    void unknownOrEmptyTokenIsRefusedAndAudited() {
        expectCode(() -> f.access.open(ADMIN, "no-such-token"), "ACCESS_NOT_FOUND");
        expectCode(() -> f.access.open(ADMIN, null), "ACCESS_NOT_FOUND");

        assertThat(audit("IDENTITY_ACCESS_DENIED", "UNKNOWN_TOKEN")).isEqualTo(2);
    }

    @Test
    void purposeIsRequiredAndWithoutItNoGrantExists() {
        expectCode(() -> f.access.grant(ADMIN, s.pngObject(), null), "REASON_REQUIRED");
        expectCode(() -> f.access.grant(ADMIN, s.pngObject(), "because"), "REASON_REQUIRED");

        assertThat(jdbc.sql("SELECT count(*) FROM identity_access_grants").query(Integer.class).single()).isZero();
        expectCode(() -> f.access.grant(ADMIN, UUID.randomUUID(), "IDENTITY_CHECK"), "OBJECT_NOT_FOUND");
    }

    @Test
    void tokenIsStoredOnlyAsAHash() {
        var grant = f.access.grant(ADMIN, s.pngObject(), "IDENTITY_CHECK");

        String stored = jdbc.sql("SELECT token_hash FROM identity_access_grants").query(String.class).single();

        assertThat(stored).isNotEqualTo(grant.token()).doesNotContain(grant.token()).hasSize(64);
    }

    @Test
    void afterTheDecisionDocumentsAreNoLongerAvailable() {
        var grant = f.access.grant(ADMIN, s.pngObject(), "IDENTITY_CHECK");
        f.review.startReview(ADMIN, s.caseId(), "REVIEW_STARTED");
        f.review.decide(ADMIN, s.caseId(), true, "DOCS_OK");

        // Уже выданный токен перестаёт работать, новый не выдаётся.
        expectCode(() -> f.access.open(ADMIN, grant.token()), "OBJECT_UNAVAILABLE");
        expectCode(() -> f.access.grant(ADMIN, s.pngObject(), "IDENTITY_CHECK"), "OBJECT_UNAVAILABLE");
        assertThat(audit("IDENTITY_ACCESS_DENIED", "OBJECT_UNAVAILABLE")).isEqualTo(2);
    }

    @Test
    void deletedObjectIsUnavailable() {
        var grant = f.access.grant(ADMIN, s.pngObject(), "IDENTITY_CHECK");
        jdbc.sql("UPDATE identity_objects SET state = 'DELETED' WHERE id = :id").param("id", s.pngObject()).update();

        expectCode(() -> f.access.open(ADMIN, grant.token()), "OBJECT_UNAVAILABLE");
    }
}
