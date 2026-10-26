package kg.jolomo.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.verification.EligibilityService.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP03-03: непроверенный и приостановленный не публикуют; правила допуска это данные; default deny. */
class EligibilityTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    private final Clock clock = new MutableClock();
    private DriverProfileService profiles;
    private ObligationConfigService obligations;
    private EligibilityService eligibility;
    private UUID driver;

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog(jdbc, clock);
        profiles = new DriverProfileService(jdbc, tx, clock, audit);
        obligations = new ObligationConfigService(jdbc, tx, clock, audit);
        eligibility = new EligibilityService(jdbc, profiles, obligations);
        driver = newUser("+996555000010");
    }

    private UUID newUser(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", phone)
                .update();
        return id;
    }

    private void approve(UUID user) {
        profiles.create(user);
        profiles.transition(user, DriverProfileStatus.SUBMITTED, user, "SELF_SERVICE");
        profiles.transition(user, DriverProfileStatus.IN_REVIEW, ADMIN, "REVIEW_STARTED");
        profiles.transition(user, DriverProfileStatus.APPROVED, ADMIN, "DOCS_OK");
    }

    @Test
    void noProfileMeansNotADriver() {
        assertThat(eligibility.canPublishTrip(driver).reason()).isEqualTo(Reason.NOT_A_DRIVER);
    }

    @Test
    void unverifiedDriverCannotPublish() {
        profiles.create(driver);

        EligibilityService.Decision d = eligibility.canPublishTrip(driver);

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(Reason.NOT_APPROVED);
    }

    @Test
    void approvedDriverStillDeniedWhileObligationsAreOff() {
        approve(driver);

        EligibilityService.Decision d = eligibility.canPublishTrip(driver);

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(Reason.OBLIGATIONS_NOT_LOADED);
    }

    @Test
    void approvedDriverWithLoadedObligationsMayPublish() {
        approve(driver);
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "legal-2026-1", ADMIN);

        assertThat(eligibility.canPublishTrip(driver).allowed()).isTrue();
    }

    @Test
    void obligationsAreOffByDefault() {
        assertThat(obligations.isEnabled(ObligationConfigService.TRIP_PUBLISH)).isFalse();
        assertThat(obligations.isEnabled(ObligationConfigService.PARCEL_CREATE)).isFalse();
    }

    @Test
    void suspendedDriverCannotPublishAndLiftRestoresPreviousState() {
        approve(driver);
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);

        profiles.suspend(driver, ADMIN, "SUSPICIOUS_ACTIVITY");
        assertThat(eligibility.canPublishTrip(driver).reason()).isEqualTo(Reason.NOT_APPROVED);

        profiles.liftSuspension(driver, ADMIN, "CLEARED");
        assertThat(profiles.find(driver).orElseThrow().status()).isEqualTo(DriverProfileStatus.APPROVED);
        assertThat(eligibility.canPublishTrip(driver).allowed()).isTrue();
    }

    @Test
    void liftingSuspensionOfUnverifiedDoesNotGrantApproval() {
        profiles.create(driver);
        profiles.suspend(driver, ADMIN, "SUSPICIOUS_ACTIVITY");
        profiles.liftSuspension(driver, ADMIN, "CLEARED");

        assertThat(profiles.find(driver).orElseThrow().status()).isEqualTo(DriverProfileStatus.UNVERIFIED);
    }

    @Test
    void rulesAreDataNotCode() {
        approve(driver);
        obligations.load(ObligationConfigService.TRIP_PUBLISH, "v1", ADMIN);
        assertThat(eligibility.canPublishTrip(driver).allowed()).isTrue();

        // Исход заключения юриста: APPROVED больше не достаточно. Меняется только таблица.
        jdbc.sql("DELETE FROM eligibility_rules WHERE action = 'PUBLISH_TRIP'").update();

        assertThat(eligibility.canPublishTrip(driver).allowed()).isFalse();
    }

    @Test
    void loadingObligationsNeedsVersionAndKnownKey() {
        assertThatThrownBy(() -> obligations.load(ObligationConfigService.TRIP_PUBLISH, " ", ADMIN))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VERSION_REQUIRED"));
        assertThatThrownBy(() -> obligations.load("NOPE", "v1", ADMIN))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("OBLIGATION_KEY_UNKNOWN"));
    }
}
