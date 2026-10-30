package kg.jolomo.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** SP11-01: полная таблица прав. Любое изменение прав ролей должно быть видно здесь отдельной правкой. */
class AdminRolePermissionsTest {

    private static final Map<AdminRole, Set<AdminPermission>> EXPECTED = new EnumMap<>(AdminRole.class);

    static {
        EXPECTED.put(AdminRole.REVIEWER, EnumSet.of(AdminPermission.VERIFICATION_REVIEW));
        EXPECTED.put(AdminRole.SUPPORT,
                EnumSet.of(AdminPermission.INCIDENT_RECORD, AdminPermission.DRIVER_SUSPEND, AdminPermission.ACTIVITY_FLAG));
        EXPECTED.put(AdminRole.OWNER, EnumSet.allOf(AdminPermission.class));
    }

    @Test
    void everyRoleHasExactlyTheExpectedPermissions() {
        for (AdminRole role : AdminRole.values()) {
            for (AdminPermission permission : AdminPermission.values()) {
                assertThat(role.allows(permission))
                        .as("%s -> %s", role, permission)
                        .isEqualTo(EXPECTED.get(role).contains(permission));
            }
        }
    }

    @Test
    void supportCannotReviewDocumentsOrManageAccountsAndReviewerCannotSupport() {
        assertThat(AdminRole.SUPPORT.allows(AdminPermission.VERIFICATION_REVIEW)).isFalse();
        assertThat(AdminRole.SUPPORT.allows(AdminPermission.ADMIN_MANAGE)).isFalse();
        assertThat(AdminRole.REVIEWER.allows(AdminPermission.DRIVER_SUSPEND)).isFalse();
        assertThat(AdminRole.REVIEWER.allows(AdminPermission.ACTIVITY_FLAG)).isFalse();
        assertThat(AdminRole.REVIEWER.allows(AdminPermission.INCIDENT_RECORD)).isFalse();
    }

    @Test
    void onlyTheOwnerManagesAccounts() {
        for (AdminRole role : AdminRole.values()) {
            assertThat(role.allows(AdminPermission.ADMIN_MANAGE)).isEqualTo(role == AdminRole.OWNER);
        }
    }

    /** SP04-03: изъятие из удаления (legal hold) ставит и снимает только владелец. */
    @Test
    void onlyTheOwnerManagesLegalHolds() {
        for (AdminRole role : AdminRole.values()) {
            assertThat(role.allows(AdminPermission.LEGAL_HOLD)).as(role.name()).isEqualTo(role == AdminRole.OWNER);
        }
    }
}
