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

/** SP04-03: версия уведомления хранится у пользователя, изменение версии оставляет строку аудита. */
class PrivacyNoticeServiceTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    private final MutableClock clock = new MutableClock();
    private PrivacyNoticeService notices;
    private UUID user;

    @BeforeEach
    void setUp() {
        notices = new PrivacyNoticeService(jdbc, tx, clock, new AuditLog(jdbc, clock));
        user = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, '+996555000101', 'ACTIVE', now())")
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
    void thereIsExactlyOneCurrentVersionAndNewUsersHaveAcceptedNothing() {
        assertThat(notices.current()).hasValueSatisfying(n -> assertThat(n.version()).isEqualTo("WORKING-1"));
        assertThat(count("SELECT count(*) FROM privacy_notices WHERE is_current")).isEqualTo(1);
        assertThat(notices.acceptanceOf(user)).isEmpty();
    }

    @Test
    void acceptingStoresTheVersionOnTheUserAndWritesAudit() {
        var a = notices.accept(user, "WORKING-1");

        assertThat(a.version()).isEqualTo("WORKING-1");
        assertThat(notices.acceptanceOf(user)).hasValueSatisfying(x -> assertThat(x.version()).isEqualTo("WORKING-1"));
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'PRIVACY_NOTICE_ACCEPTED' AND subject_id IS NOT NULL "
                + "AND actor_user_id = subject_id AND from_state IS NULL AND to_state = 'WORKING-1'")).isEqualTo(1);
    }

    @Test
    void acceptingTheSameVersionAgainChangesNothingAndDoesNotDuplicateAudit() {
        var first = notices.accept(user, "WORKING-1");
        clock.advance(Duration.ofHours(3));

        var again = notices.accept(user, "WORKING-1");

        assertThat(again.acceptedAt()).isEqualTo(first.acceptedAt());
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'PRIVACY_NOTICE_ACCEPTED'")).isEqualTo(1);
    }

    @Test
    void invalidUnknownAndMissingUserAreRefused() {
        expectCode(() -> notices.accept(user, "bad version!"), "NOTICE_VERSION_INVALID");
        expectCode(() -> notices.accept(user, null), "NOTICE_VERSION_INVALID");
        expectCode(() -> notices.accept(user, "NOPE-9"), "NOTICE_VERSION_UNKNOWN");
        expectCode(() -> notices.accept(UUID.randomUUID(), "WORKING-1"), "USER_NOT_FOUND");
        assertThat(notices.acceptanceOf(user)).isEmpty();
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'PRIVACY_NOTICE_ACCEPTED'")).isZero();
    }

    @Test
    void publishingMakesTheNewVersionCurrentAndKeepsWhatPeopleAcceptedBefore() {
        notices.accept(user, "WORKING-1");

        notices.publish(ADMIN, "V2");

        assertThat(notices.current()).hasValueSatisfying(n -> assertThat(n.version()).isEqualTo("V2"));
        assertThat(count("SELECT count(*) FROM privacy_notices WHERE is_current")).isEqualTo(1);
        // старая версия у пользователя остаётся как была, согласие на новую сам никто не получает
        assertThat(notices.acceptanceOf(user)).hasValueSatisfying(x -> assertThat(x.version()).isEqualTo("WORKING-1"));
        expectCode(() -> notices.accept(user, "WORKING-1"), "NOTICE_NOT_CURRENT");

        notices.accept(user, "V2");
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'PRIVACY_NOTICE_ACCEPTED' "
                + "AND from_state = 'WORKING-1' AND to_state = 'V2'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'PRIVACY_NOTICE_PUBLISHED' AND to_state = 'V2' "
                + "AND actor_user_id IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void publishingAnExistingVersionIsRefused() {
        expectCode(() -> notices.publish(ADMIN, "WORKING-1"), "NOTICE_VERSION_EXISTS");
        expectCode(() -> notices.publish(ADMIN, "bad version!"), "NOTICE_VERSION_INVALID");
    }

    @Test
    void theDatabaseKeepsVersionAndTimeTogetherAndOnlyKnownVersions() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE users SET notice_version = 'WORKING-1' WHERE id = :id")
                        .param("id", user)
                        .update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE users SET notice_version = 'NOPE-9', notice_accepted_at = now() WHERE id = :id")
                        .param("id", user)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }
}
