package kg.jolomo.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** SP11-01: пометка это запись с причиной и аудитом, и ничего больше. */
class ActivityFlagServiceTest extends PostgisTestBase {

    private static final UUID SUPPORT = UUID.randomUUID();

    private final MutableClock clock = new MutableClock();
    private ActivityFlagService flags;
    private UUID user;

    @BeforeEach
    void setUp() {
        flags = new ActivityFlagService(jdbc, tx, clock, new AuditLog(jdbc, clock));
        user = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000090', 'ACTIVE', now())")
                .param("id", user)
                .update();
    }

    private void expectCode(Runnable r, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void flagIsRecordedWithReasonAndAudit() {
        var flag = flags.record(SUPPORT, user, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED");

        assertThat(flag.userId()).isEqualTo(user);
        assertThat(count("SELECT count(*) FROM suspicious_activity WHERE reason_code = 'RECIPIENT_CALLED' AND recorded_by IS NOT NULL"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'SUSPICIOUS_ACTIVITY_FLAGGED' "
                + "AND actor_user_id IS NOT NULL AND reason_code = 'RECIPIENT_CALLED' AND to_state = 'MULTIPLE_ACCOUNTS'")).isEqualTo(1);
    }

    @Test
    void flaggingChangesNothingAboutTheUser() {
        String before = jdbc.sql("SELECT status || '|' || coalesce(last_login_at::text, '') FROM users WHERE id = :id")
                .param("id", user)
                .query(String.class)
                .single();

        flags.record(SUPPORT, user, "FAKE_RECIPIENT_PHONE", "RECIPIENT_CALLED");

        assertThat(jdbc.sql("SELECT status || '|' || coalesce(last_login_at::text, '') FROM users WHERE id = :id")
                        .param("id", user)
                        .query(String.class)
                        .single())
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM driver_profiles")).isZero();
    }

    @Test
    void inputIsValidatedAndNothingIsWrittenOnRefusal() {
        expectCode(() -> flags.record(SUPPORT, user, "MULTIPLE_ACCOUNTS", null), "REASON_REQUIRED");
        expectCode(() -> flags.record(SUPPORT, user, "MULTIPLE_ACCOUNTS", "free text"), "REASON_REQUIRED");
        expectCode(() -> flags.record(SUPPORT, user, "NO_SUCH_KIND", "RECIPIENT_CALLED"), "INVALID_SUSPICION_KIND");
        expectCode(() -> flags.record(SUPPORT, user, null, "RECIPIENT_CALLED"), "INVALID_SUSPICION_KIND");
        expectCode(() -> flags.record(SUPPORT, UUID.randomUUID(), "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED"), "USER_NOT_FOUND");
        expectCode(() -> flags.record(SUPPORT, null, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED"), "USER_NOT_FOUND");
        jdbc.sql("UPDATE suspicion_kinds SET active = false WHERE code = 'MULTIPLE_ACCOUNTS'").update();
        expectCode(() -> flags.record(SUPPORT, user, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED"), "INVALID_SUSPICION_KIND");

        assertThat(count("SELECT count(*) FROM suspicious_activity")).isZero();
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'SUSPICIOUS_ACTIVITY_FLAGGED'")).isZero();
    }

    @Test
    void flagsCannotBeChangedOrDeleted() {
        flags.record(SUPPORT, user, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED");

        assertThatThrownBy(() -> jdbc.sql("UPDATE suspicious_activity SET kind = 'OTHER'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM suspicious_activity").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void listIsNewestFirstAndPerUser() {
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000091', 'ACTIVE', now())")
                .param("id", other)
                .update();
        flags.record(SUPPORT, user, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED");
        clock.advance(Duration.ofMinutes(1));
        flags.record(SUPPORT, user, "OTHER", "RECIPIENT_CALLED");
        flags.record(SUPPORT, other, "OTHER", "RECIPIENT_CALLED");

        var list = flags.list(user, 50);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).kind()).isEqualTo("OTHER");
    }
}
