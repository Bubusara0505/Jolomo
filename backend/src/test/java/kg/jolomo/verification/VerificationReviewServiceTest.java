package kg.jolomo.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SP04-02: очередь проверки и решение. */
class VerificationReviewServiceTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    @TempDir
    Path dir;

    private ReviewFixture f;

    @BeforeEach
    void setUp() {
        f = new ReviewFixture(jdbc, tx, dir, TEST_SMS_KEY);
    }

    private void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void queueListsOldestFirstWithoutPersonalData() {
        var first = f.submitted();
        f.clock.advance(Duration.ofMinutes(1));
        var second = f.submitted();

        var queue = f.review.queue(null, 50);

        assertThat(queue).extracting(VerificationReviewService.QueueItem::caseId).containsExactly(first.caseId(), second.caseId());
        assertThat(queue.get(0).files()).isEqualTo(2);
        assertThat(queue.get(0).toString()).doesNotContain("+996");
        assertThat(f.review.queue("IN_REVIEW", 50)).isEmpty();
        expectCode(() -> f.review.queue("APPROVED", 50), "INVALID_STATUS");
    }

    @Test
    void detailShowsFileMetadataButNoBytesOrKeys() {
        var s = f.submitted();

        var d = f.review.detail(s.caseId());

        assertThat(d.files()).hasSize(2);
        assertThat(d.files()).extracting(VerificationReviewService.FileInfo::contentType)
                .containsExactlyInAnyOrder("image/png", "application/pdf");
        expectCode(() -> f.review.detail(UUID.randomUUID()), "CASE_NOT_FOUND");
    }

    @Test
    void approveFlowMovesCaseAndProfileAndIsAudited() {
        var s = f.submitted();

        f.review.startReview(ADMIN, s.caseId(), "REVIEW_STARTED");
        assertThat(f.profiles.find(s.driverId()).orElseThrow().status()).isEqualTo(DriverProfileStatus.IN_REVIEW);
        f.review.decide(ADMIN, s.caseId(), true, "DOCS_OK");

        assertThat(f.profiles.find(s.driverId()).orElseThrow().status()).isEqualTo(DriverProfileStatus.APPROVED);
        assertThat(jdbc.sql("SELECT status FROM verification_cases WHERE id = :c").param("c", s.caseId()).query(String.class).single())
                .isEqualTo("APPROVED");
        assertThat(count("SELECT count(*) FROM audit_events WHERE actor_user_id IS NOT NULL AND action IN "
                + "('VERIFICATION_REVIEW_STARTED', 'VERIFICATION_APPROVED')")).isEqualTo(2);
        assertThat(f.review.queue(null, 50)).isEmpty();
    }

    @Test
    void rejectedDriverMayResubmitANewCase() {
        var s = f.submitted();
        f.review.startReview(ADMIN, s.caseId(), "REVIEW_STARTED");
        f.review.decide(ADMIN, s.caseId(), false, "DOCS_UNCLEAR");

        assertThat(f.profiles.find(s.driverId()).orElseThrow().status()).isEqualTo(DriverProfileStatus.REJECTED);
        var again = f.cases.submit(s.driverId(), java.util.List.of(new VerificationCaseService.FilePart(
                "image/png", kg.jolomo.testsupport.Samples.png(8, 8).length, kg.jolomo.testsupport.Samples.png(8, 8))));

        assertThat(again.caseId()).isNotEqualTo(s.caseId());
        assertThat(f.review.queue(null, 50)).hasSize(1);
    }

    @Test
    void stepsOutOfOrderAreRefused() {
        var s = f.submitted();

        expectCode(() -> f.review.decide(ADMIN, s.caseId(), true, "DOCS_OK"), "INVALID_CASE_STATE"); // без начала проверки
        f.review.startReview(ADMIN, s.caseId(), "REVIEW_STARTED");
        expectCode(() -> f.review.startReview(ADMIN, s.caseId(), "REVIEW_STARTED"), "INVALID_CASE_STATE");
        f.review.decide(ADMIN, s.caseId(), true, "DOCS_OK");
        expectCode(() -> f.review.decide(ADMIN, s.caseId(), false, "CHANGED_MIND"), "INVALID_CASE_STATE");
        expectCode(() -> f.review.startReview(ADMIN, UUID.randomUUID(), "REVIEW_STARTED"), "CASE_NOT_FOUND");
    }

    @Test
    void reasonCodeIsRequiredAndNothingChangesWithoutIt() {
        var s = f.submitted();

        expectCode(() -> f.review.startReview(ADMIN, s.caseId(), "nope"), "REASON_REQUIRED");
        expectCode(() -> f.review.startReview(ADMIN, s.caseId(), null), "REASON_REQUIRED");

        assertThat(f.profiles.find(s.driverId()).orElseThrow().status()).isEqualTo(DriverProfileStatus.SUBMITTED);
    }
}
