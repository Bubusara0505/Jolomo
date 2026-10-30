package kg.jolomo.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** SP04-03: legal hold ставится и снимается с причиной и аудитом, действующий hold у пользователя только один. */
class LegalHoldServiceTest extends PostgisTestBase {

    private static final UUID OWNER = UUID.randomUUID();

    private final MutableClock clock = new MutableClock();
    private LegalHoldService holds;
    private UUID user;

    @BeforeEach
    void setUp() {
        holds = new LegalHoldService(jdbc, tx, clock, new AuditLog(jdbc, clock));
        user = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000111', 'ACTIVE', now())")
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
    void placingAHoldRecordsReasonAdminAndAudit() {
        var hold = holds.place(OWNER, user, "LEGAL_REQUEST_1");

        assertThat(hold.userId()).isEqualTo(user);
        assertThat(hold.releasedAt()).isNull();
        assertThat(count("SELECT count(*) FROM legal_holds WHERE user_id IS NOT NULL AND placed_by IS NOT NULL AND released_at IS NULL"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'LEGAL_HOLD_PLACED' AND reason_code = 'LEGAL_REQUEST_1' "
                + "AND actor_user_id IS NOT NULL AND to_state = 'HELD'")).isEqualTo(1);
    }

    @Test
    void aSecondActiveHoldOnTheSameUserIsRefusedAndTheDatabaseAgrees() {
        holds.place(OWNER, user, "LEGAL_REQUEST_1");

        expectCode(() -> holds.place(OWNER, user, "LEGAL_REQUEST_2"), "LEGAL_HOLD_ALREADY_ACTIVE");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO legal_holds (id, user_id, reason_code, placed_by, placed_at) "
                                + "VALUES (:id, :u, 'SECOND_ONE', :a, now())")
                        .param("id", UUID.randomUUID())
                        .param("u", user)
                        .param("a", OWNER)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void releaseClosesTheHoldAuditsItAndAllowsANewOne() {
        var hold = holds.place(OWNER, user, "LEGAL_REQUEST_1");

        var released = holds.release(OWNER, hold.id(), "REQUEST_WITHDRAWN");

        assertThat(released.releasedAt()).isNotNull();
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'LEGAL_HOLD_RELEASED' AND reason_code = 'REQUEST_WITHDRAWN' "
                + "AND from_state = 'HELD' AND to_state = 'RELEASED'")).isEqualTo(1);
        expectCode(() -> holds.release(OWNER, hold.id(), "REQUEST_WITHDRAWN"), "LEGAL_HOLD_ALREADY_RELEASED");
        assertThat(holds.place(OWNER, user, "LEGAL_REQUEST_3").releasedAt()).isNull();
        assertThat(holds.list(user)).hasSize(2);
    }

    @Test
    void reasonUserAndHoldMustExist() {
        expectCode(() -> holds.place(OWNER, user, null), "REASON_REQUIRED");
        expectCode(() -> holds.place(OWNER, user, "free text with spaces"), "REASON_REQUIRED");
        expectCode(() -> holds.place(OWNER, UUID.randomUUID(), "LEGAL_REQUEST_1"), "USER_NOT_FOUND");
        expectCode(() -> holds.place(OWNER, null, "LEGAL_REQUEST_1"), "USER_NOT_FOUND");
        expectCode(() -> holds.release(OWNER, UUID.randomUUID(), "REQUEST_WITHDRAWN"), "LEGAL_HOLD_NOT_FOUND");
        assertThat(count("SELECT count(*) FROM legal_holds")).isZero();
    }
}
