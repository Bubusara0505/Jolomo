package kg.jolomo.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.admin.AdminService.Enrollment;
import kg.jolomo.admin.AdminService.LoginSession;
import kg.jolomo.shared.AesGcmBox;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP04-01: пароль и TOTP обязательны; блокировка, повтор кода, восстановление, отзыв и сброс. */
class AdminServiceTest extends PostgisTestBase {

    private final MutableClock clock = new MutableClock();
    private AdminService admins;

    @BeforeEach
    void setUp() {
        AdminSettings settings = new AdminSettings(Duration.ofMinutes(30), 5, Duration.ofMinutes(15), 1000, true);
        admins = new AdminService(jdbc, tx, clock, new AuditLog(jdbc, clock), new PasswordHasher(1000),
                new AesGcmBox(TEST_SMS_KEY, "TEST"), settings);
    }

    private Enrollment owner() {
        return admins.create("owner", "Owner", AdminRole.OWNER, null, "BOOTSTRAP");
    }

    private String code(Enrollment e) {
        return Totp.code(Base32.decode(e.totpSecret()), Totp.stepAt(clock.instant().getEpochSecond()));
    }

    private Optional<LoginSession> login(Enrollment e) {
        return admins.login(e.login(), e.password(), code(e));
    }

    private void nextTotpStep() {
        clock.advance(Duration.ofSeconds(30));
    }

    private void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void secretsAreShownOnceAndNeverStoredInTheClear() {
        Enrollment e = owner();

        assertThat(e.password()).hasSize(20);
        assertThat(e.recoveryCodes()).hasSize(8);
        assertThat(e.otpauthUri()).startsWith("otpauth://totp/JolOmo%3Aowner?secret=" + e.totpSecret());

        String hash = jdbc.sql("SELECT password_hash FROM admin_accounts").query(String.class).single();
        assertThat(hash).doesNotContain(e.password());
        byte[] sealed = jdbc.sql("SELECT totp_secret_enc FROM admin_accounts").query(byte[].class).single();
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(e.totpSecret());
        assertThat(count("SELECT count(*) FROM admin_recovery_codes")).isEqualTo(8);
        String firstCode = e.recoveryCodes().get(0).replace("-", "");
        assertThat(jdbc.sql("SELECT count(*) FROM admin_recovery_codes WHERE code_hash = :c")
                        .param("c", firstCode)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void loginNeedsPasswordAndSecondFactor() {
        Enrollment e = owner();

        assertThat(admins.login(e.login(), "wrong-password", code(e))).isEmpty();
        assertThat(admins.login(e.login(), e.password(), "000000")).isEmpty();
        assertThat(admins.login(e.login(), e.password(), null)).isEmpty();
        assertThat(admins.login(e.login(), e.password(), "")).isEmpty();

        LoginSession s = login(e).orElseThrow();
        assertThat(s.admin().role()).isEqualTo(AdminRole.OWNER);
        assertThat(s.recoveryCodesLeft()).isEqualTo(8);
        assertThat(admins.authenticate(s.token())).isPresent();
    }

    @Test
    void sessionExpiresAndLogoutRevokes() {
        Enrollment e = owner();
        LoginSession s = login(e).orElseThrow();
        admins.logout(s.admin(), s.token());
        assertThat(admins.authenticate(s.token())).isEmpty();

        nextTotpStep();
        LoginSession s2 = login(e).orElseThrow();
        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        assertThat(admins.authenticate(s2.token())).isEmpty();
    }

    @Test
    void totpCodeCannotBeUsedTwice() {
        Enrollment e = owner();
        String used = code(e);

        assertThat(admins.login(e.login(), e.password(), used)).isPresent();
        assertThat(admins.login(e.login(), e.password(), used)).isEmpty();

        nextTotpStep();
        assertThat(login(e)).isPresent();
    }

    @Test
    void fiveFailuresLockTheAccountEvenForCorrectCredentials() {
        Enrollment e = owner();
        for (int i = 0; i < 5; i++) {
            assertThat(admins.login(e.login(), "wrong-password", "000000")).isEmpty();
        }

        assertThat(login(e)).isEmpty();
        clock.advance(Duration.ofMinutes(14));
        assertThat(login(e)).isEmpty();
        clock.advance(Duration.ofMinutes(2));
        assertThat(login(e)).isPresent();
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'ADMIN_LOCKED'")).isEqualTo(1);
    }

    @Test
    void successResetsTheFailureCounter() {
        Enrollment e = owner();
        for (int i = 0; i < 4; i++) {
            admins.login(e.login(), "wrong-password", "000000");
        }
        assertThat(login(e)).isPresent();
        nextTotpStep();
        for (int i = 0; i < 4; i++) {
            admins.login(e.login(), "wrong-password", "000000");
        }

        assertThat(login(e)).isPresent(); // не заблокирован: счётчик начался заново
    }

    @Test
    void unknownLoginFailsQuietlyAndIsAudited() {
        assertThat(admins.login("nobody", "whatever-password", "123456")).isEmpty();

        assertThat(count("SELECT count(*) FROM audit_events WHERE reason_code = 'UNKNOWN_LOGIN'")).isEqualTo(1);
    }

    @Test
    void recoveryCodeWorksOnceAndAcceptsLooseFormatting() {
        Enrollment e = owner();
        String first = e.recoveryCodes().get(0);

        LoginSession s = admins.login(e.login(), e.password(), first).orElseThrow();
        assertThat(s.recoveryCodesLeft()).isEqualTo(7);
        assertThat(admins.login(e.login(), e.password(), first)).isEmpty();

        String loose = e.recoveryCodes().get(1).toLowerCase().replace("-", "");
        assertThat(admins.login(e.login(), e.password(), loose).orElseThrow().recoveryCodesLeft()).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM audit_events WHERE reason_code = 'RECOVERY_CODE'")).isEqualTo(2);
    }

    @Test
    void recoveryCodeIsUselessWithoutThePassword() {
        Enrollment e = owner();

        assertThat(admins.login(e.login(), "wrong-password", e.recoveryCodes().get(0))).isEmpty();
        assertThat(count("SELECT count(*) FROM admin_recovery_codes WHERE used_at IS NOT NULL")).isZero();
    }

    @Test
    void disablingRevokesSessionsAtOnceAndBlocksLogin() {
        Enrollment owner = owner();
        Enrollment reviewer = admins.create("rev1", "Reviewer", AdminRole.REVIEWER, owner.adminId(), "NEW_STAFF");
        LoginSession s = login(reviewer).orElseThrow();

        admins.disable(owner.adminId(), reviewer.adminId(), "LEFT_TEAM");

        assertThat(admins.authenticate(s.token())).isEmpty();
        nextTotpStep();
        assertThat(login(reviewer)).isEmpty();
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'ADMIN_DISABLED' AND reason_code = 'LEFT_TEAM'"))
                .isEqualTo(1);
    }

    @Test
    void managementNeedsReasonAndCannotTargetSelf() {
        Enrollment owner = owner();
        Enrollment reviewer = admins.create("rev1", "Reviewer", AdminRole.REVIEWER, owner.adminId(), "NEW_STAFF");

        expectCode(() -> admins.disable(owner.adminId(), reviewer.adminId(), "no reason"), "REASON_REQUIRED");
        expectCode(() -> admins.disable(owner.adminId(), owner.adminId(), "TESTING"), "CANNOT_TARGET_SELF");
        expectCode(() -> admins.reset(owner.adminId(), owner.adminId(), "TESTING"), "CANNOT_TARGET_SELF");
        expectCode(() -> admins.disable(owner.adminId(), UUID.randomUUID(), "TESTING"), "ADMIN_NOT_FOUND");
        expectCode(() -> admins.create("rev1", "Again", AdminRole.SUPPORT, owner.adminId(), "NEW_STAFF"), "ADMIN_EXISTS");
        expectCode(() -> admins.create("Bad Login!", "X", AdminRole.SUPPORT, owner.adminId(), "NEW_STAFF"), "INVALID_LOGIN");
    }

    @Test
    void resetReplacesEveryCredentialAndRevokesSessions() {
        Enrollment owner = owner();
        Enrollment reviewer = admins.create("rev1", "Reviewer", AdminRole.REVIEWER, owner.adminId(), "NEW_STAFF");
        LoginSession old = login(reviewer).orElseThrow();
        String oldRecovery = reviewer.recoveryCodes().get(0);

        Enrollment fresh = admins.reset(owner.adminId(), reviewer.adminId(), "LOST_DEVICE");

        assertThat(fresh.totpSecret()).isNotEqualTo(reviewer.totpSecret());
        assertThat(fresh.password()).isNotEqualTo(reviewer.password());
        assertThat(admins.authenticate(old.token())).isEmpty();
        nextTotpStep();
        assertThat(admins.login("rev1", reviewer.password(), code(fresh))).isEmpty();      // старый пароль
        assertThat(admins.login("rev1", fresh.password(), oldRecovery)).isEmpty();         // старый код восстановления
        assertThat(admins.login("rev1", fresh.password(), Totp.code(Base32.decode(reviewer.totpSecret()),
                Totp.stepAt(clock.instant().getEpochSecond())))).isEmpty();                // старый секрет TOTP
        assertThat(login(fresh)).isPresent();
    }

    @Test
    void changingPasswordKeepsCurrentSessionAndDropsOthers() {
        Enrollment e = owner();
        LoginSession a = login(e).orElseThrow();
        nextTotpStep();
        LoginSession b = login(e).orElseThrow();

        expectCode(() -> admins.changePassword(b.admin(), b.token(), "wrong", "a-new-long-password"), "ADMIN_PASSWORD_WRONG");
        expectCode(() -> admins.changePassword(b.admin(), b.token(), e.password(), "short"), "PASSWORD_TOO_WEAK");

        admins.changePassword(b.admin(), b.token(), e.password(), "a-new-long-password");

        assertThat(admins.authenticate(a.token())).isEmpty();
        assertThat(admins.authenticate(b.token())).isPresent();
        nextTotpStep();
        assertThat(admins.login(e.login(), e.password(), code(e))).isEmpty();
        assertThat(admins.login(e.login(), "a-new-long-password", code(e))).isPresent();
    }

    @Test
    void rolesHaveLeastPrivilege() {
        assertThat(AdminRole.REVIEWER.allows(AdminPermission.VERIFICATION_REVIEW)).isTrue();
        assertThat(AdminRole.REVIEWER.allows(AdminPermission.INCIDENT_RECORD)).isFalse();
        assertThat(AdminRole.REVIEWER.allows(AdminPermission.ADMIN_MANAGE)).isFalse();
        assertThat(AdminRole.SUPPORT.allows(AdminPermission.INCIDENT_RECORD)).isTrue();
        assertThat(AdminRole.SUPPORT.allows(AdminPermission.VERIFICATION_REVIEW)).isFalse();
        assertThat(AdminRole.SUPPORT.allows(AdminPermission.ADMIN_MANAGE)).isFalse();
        for (AdminPermission p : AdminPermission.values()) {
            assertThat(AdminRole.OWNER.allows(p)).isTrue();
        }
    }

    @Test
    void everyPrivilegedActionLeavesAnAuditRow() {
        Enrollment owner = owner();
        admins.login(owner.login(), "wrong-password", "000000");
        LoginSession s = login(owner).orElseThrow();
        admins.recordDenied(s.admin(), AdminPermission.VERIFICATION_REVIEW);

        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'ADMIN_CREATED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'ADMIN_LOGIN_FAILED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'ADMIN_LOGIN'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'ADMIN_ACCESS_DENIED'")).isEqualTo(1);
    }
}
