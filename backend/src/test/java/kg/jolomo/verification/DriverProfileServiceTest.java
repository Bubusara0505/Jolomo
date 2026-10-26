package kg.jolomo.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP03-03: переходы допуска, коды причин и аудит. */
class DriverProfileServiceTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    private final Clock clock = new MutableClock();
    private DriverProfileService profiles;
    private UUID user;

    @BeforeEach
    void setUp() {
        profiles = new DriverProfileService(jdbc, tx, clock, new AuditLog(jdbc, clock));
        user = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000020', 'ACTIVE', now())")
                .param("id", user)
                .update();
    }

    private void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void createIsIdempotentAndStartsUnverified() {
        assertThat(profiles.create(user).status()).isEqualTo(DriverProfileStatus.UNVERIFIED);
        assertThat(profiles.create(user).status()).isEqualTo(DriverProfileStatus.UNVERIFIED);

        assertThat(jdbc.sql("SELECT count(*) FROM driver_profiles").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'DRIVER_PROFILE_CREATED'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void happyPathIsAudited() {
        profiles.create(user);
        profiles.transition(user, DriverProfileStatus.SUBMITTED, user, "SELF_SERVICE");
        profiles.transition(user, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
        profiles.transition(user, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK");

        assertThat(profiles.find(user).orElseThrow().status()).isEqualTo(DriverProfileStatus.APPROVED);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'DRIVER_PROFILE_TRANSITION'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void skippingReviewIsForbidden() {
        profiles.create(user);

        expectCode(() -> profiles.transition(user, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK"), "INVALID_TRANSITION");
        assertThat(profiles.find(user).orElseThrow().status()).isEqualTo(DriverProfileStatus.UNVERIFIED);
    }

    @Test
    void rejectedCanResubmitButNotJumpToApproved() {
        profiles.create(user);
        profiles.transition(user, DriverProfileStatus.SUBMITTED, user, "SELF_SERVICE");
        profiles.transition(user, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
        profiles.transition(user, DriverProfileStatus.REJECTED, ADMIN, "DOCS_UNCLEAR");

        expectCode(() -> profiles.transition(user, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK"), "INVALID_TRANSITION");
        profiles.transition(user, DriverProfileStatus.SUBMITTED, user, "SELF_SERVICE");
    }

    @Test
    void reasonCodeIsRequired() {
        profiles.create(user);

        expectCode(() -> profiles.transition(user, DriverProfileStatus.SUBMITTED, user, null), "REASON_REQUIRED");
        expectCode(() -> profiles.transition(user, DriverProfileStatus.SUBMITTED, user, "lower case"), "REASON_REQUIRED");
        expectCode(() -> profiles.suspend(user, ADMIN, ""), "REASON_REQUIRED");
    }

    @Test
    void suspendedProfileHasNoOrdinaryTransitions() {
        profiles.create(user);
        profiles.suspend(user, ADMIN, "SUSPICIOUS_ACTIVITY");

        expectCode(() -> profiles.transition(user, DriverProfileStatus.SUBMITTED, user, "SELF_SERVICE"), "INVALID_TRANSITION");
        expectCode(() -> profiles.suspend(user, ADMIN, "SUSPICIOUS_ACTIVITY"), "INVALID_TRANSITION");
    }

    @Test
    void missingProfileIsNotFound() {
        expectCode(() -> profiles.transition(user, DriverProfileStatus.SUBMITTED, user, "SELF_SERVICE"), "DRIVER_PROFILE_NOT_FOUND");
    }
}
