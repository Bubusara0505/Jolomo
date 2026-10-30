package kg.jolomo.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * SP04-03: задание удаления снимает только просроченных синтетических пользователей, пропускает legal hold,
 * никогда не трогает реальных людей и пишет аудит. Сроки для реальных людей пусты и здесь не задаются.
 */
class SyntheticPurgeTest extends PostgisTestBase {

    private static final UUID ADMIN = UUID.randomUUID();

    private final MutableClock clock = new MutableClock();
    private final List<UUID> erased = new ArrayList<>();
    private AuditLog audit;
    private SyntheticDataService synthetic;
    private LegalHoldService holds;
    private int phoneSeq = 0;

    @BeforeEach
    void setUp() {
        audit = new AuditLog(jdbc, clock);
        synthetic = new SyntheticDataService(jdbc, tx, clock, audit, erased::add);
        holds = new LegalHoldService(jdbc, tx, clock, audit);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, phone, status, created_at) VALUES (:id, :p, 'ACTIVE', now())")
                .param("id", id)
                .param("p", String.format("+99655511%04d", ++phoneSeq))
                .update();
        return id;
    }

    private UUID expiredSynthetic() {
        UUID id = user();
        synthetic.markSynthetic(ADMIN, id, clock.instant().plus(Duration.ofHours(1)));
        return id;
    }

    private boolean exists(UUID user) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id)").param("id", user).query(Boolean.class).single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void onlyAnExpiredSyntheticUserIsDeleted() {
        UUID soon = expiredSynthetic();

        assertThat(synthetic.purgeExpired().deleted()).isZero();
        assertThat(exists(soon)).isTrue();

        clock.advance(Duration.ofHours(2));
        var result = synthetic.purgeExpired();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(exists(soon)).isFalse();
    }

    @Test
    void deletionIsAuditedAndTheMarkingToo() {
        UUID id = expiredSynthetic();
        clock.advance(Duration.ofHours(2));

        synthetic.purgeExpired();

        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'SYNTHETIC_USER_PURGED' AND subject_type = 'USER' "
                + "AND actor_user_id IS NULL AND reason_code = 'SYNTHETIC_EXPIRED' AND to_state = 'DELETED' AND subject_id = '" + id + "'"))
                .isEqualTo(1);
        // аудит удаления переживает пользователя
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'USER_MARKED_SYNTHETIC' AND subject_id = '" + id + "'")).isEqualTo(1);
    }

    @Test
    void aRealPersonIsNeverDeletedHoweverOldTheyAre() {
        UUID real = user();

        clock.advance(Duration.ofDays(3650));
        var result = synthetic.purgeExpired();

        assertThat(result.deleted()).isZero();
        assertThat(exists(real)).isTrue();
        // база не даёт вешать срок на несинтетическую строку
        assertThatThrownBy(() -> jdbc.sql("UPDATE users SET synthetic_expires_at = now() WHERE id = :id").param("id", real).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aSyntheticUserWithoutAnExpiryIsNotDeletedAndTheMarkerRequiresOne() {
        UUID id = user();
        jdbc.sql("UPDATE users SET synthetic = true WHERE id = :id").param("id", id).update();

        clock.advance(Duration.ofDays(3650));

        assertThat(synthetic.purgeExpired().deleted()).isZero();
        assertThat(exists(id)).isTrue();
        assertThatThrownBy(() -> synthetic.markSynthetic(ADMIN, id, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SYNTHETIC_EXPIRY_REQUIRED"));
        assertThatThrownBy(() -> synthetic.markSynthetic(ADMIN, UUID.randomUUID(), clock.instant()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("USER_NOT_FOUND"));
    }

    @Test
    void legalHoldExcludesTheUserFromDeletionUntilItIsReleased() {
        UUID held = expiredSynthetic();
        UUID free = expiredSynthetic();
        var hold = holds.place(ADMIN, held, "LEGAL_REQUEST_1");
        clock.advance(Duration.ofHours(2));

        var result = synthetic.purgeExpired();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.skippedLegalHold()).isEqualTo(1);
        assertThat(exists(held)).isTrue();
        assertThat(exists(free)).isFalse();
        assertThat(count("SELECT count(*) FROM audit_events WHERE action = 'SYNTHETIC_USER_PURGED' AND subject_id = '" + held + "'")).isZero();

        holds.release(ADMIN, hold.id(), "REQUEST_WITHDRAWN");
        assertThat(synthetic.purgeExpired().deleted()).isEqualTo(1);
        assertThat(exists(held)).isFalse();
    }

    @Test
    void aHoldPlacedAfterTheCandidateListWasBuiltStillStopsTheDeletion() {
        UUID id = expiredSynthetic();
        clock.advance(Duration.ofHours(2));
        holds.place(ADMIN, id, "LEGAL_REQUEST_1");

        // purgeOne перепроверяет hold под блокировкой строки пользователя
        assertThat(synthetic.purgeOne(id, Set.of(id))).isNull();
        assertThat(exists(id)).isTrue();
    }

    @Test
    void theUsersOwnRowsGoWithHimAndEverybodyElsesStay() {
        UUID gone = expiredSynthetic();
        UUID other = user();
        ActivityFlagService flags = new ActivityFlagService(jdbc, tx, clock, audit);
        flags.record(ADMIN, gone, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED");
        flags.record(ADMIN, other, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED");
        String phone = jdbc.sql("SELECT phone FROM users WHERE id = :id").param("id", gone).query(String.class).single();
        jdbc.sql("INSERT INTO auth_challenges (id, phone, purpose, expires_at, attempts_left, status, created_at) "
                        + "VALUES (:id, :p, 'LOGIN', now() + interval '5 minutes', 5, 'ACTIVE', now())")
                .param("id", UUID.randomUUID())
                .param("p", phone)
                .update();
        clock.advance(Duration.ofHours(2));

        synthetic.purgeExpired();

        assertThat(count("SELECT count(*) FROM suspicious_activity WHERE user_id = '" + gone + "'")).isZero();
        assertThat(count("SELECT count(*) FROM suspicious_activity WHERE user_id = '" + other + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM auth_challenges")).isZero();
        assertThat(exists(other)).isTrue();
    }

    @Test
    void identityObjectsAndTheirFilesAreRemovedAfterTheCommit() {
        UUID id = expiredSynthetic();
        UUID caseId = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        jdbc.sql("INSERT INTO verification_cases (id, driver_user_id, status, created_at) VALUES (:c, :u, 'SUBMITTED', now())")
                .param("c", caseId)
                .param("u", id)
                .update();
        jdbc.sql("INSERT INTO identity_objects (id, owner_user_id, case_id, storage_key, content_type, size_bytes, sha256, state, created_at) "
                        + "VALUES (:o, :u, :c, :k, 'image/png', 1, 'x', 'STORED', now())")
                .param("o", object)
                .param("u", id)
                .param("c", caseId)
                .param("k", "ab/" + object + ".enc")
                .update();
        clock.advance(Duration.ofHours(2));

        var result = synthetic.purgeExpired();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(erased).containsExactly(object);
        assertThat(count("SELECT count(*) FROM identity_objects")).isZero();
        assertThat(count("SELECT count(*) FROM verification_cases")).isZero();
    }

    @Test
    void aFailedFileErasureIsCountedAndDoesNotUndoTheCommittedDeletion() {
        SyntheticDataService failing = new SyntheticDataService(jdbc, tx, clock, audit, o -> {
            throw new IllegalStateException("диск недоступен");
        });
        UUID id = expiredSynthetic();
        UUID caseId = UUID.randomUUID();
        UUID object = UUID.randomUUID();
        jdbc.sql("INSERT INTO verification_cases (id, driver_user_id, status, created_at) VALUES (:c, :u, 'SUBMITTED', now())")
                .param("c", caseId)
                .param("u", id)
                .update();
        jdbc.sql("INSERT INTO identity_objects (id, owner_user_id, case_id, storage_key, content_type, size_bytes, sha256, state, created_at) "
                        + "VALUES (:o, :u, :c, :k, 'image/png', 1, 'x', 'STORED', now())")
                .param("o", object)
                .param("u", id)
                .param("c", caseId)
                .param("k", "ab/" + object + ".enc")
                .update();
        clock.advance(Duration.ofHours(2));

        var result = failing.purgeExpired();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.fileErrors()).isEqualTo(1);
        assertThat(exists(id)).isFalse();
    }

    @Test
    void appendOnlyStillHoldsEverywhereExceptInsideThePurgeTransaction() {
        UUID id = user();
        new ActivityFlagService(jdbc, tx, clock, audit).record(ADMIN, id, "MULTIPLE_ACCOUNTS", "RECIPIENT_CALLED");

        // обычное удаление запрещено
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM suspicious_activity").update()).isInstanceOf(DataAccessException.class);
        // исключение действует только для incidents и suspicious_activity; аудит неприкосновенен даже с выставленным признаком
        assertThatThrownBy(() -> tx.execute(status -> {
                    jdbc.sql("SELECT set_config('jolomo.purge_synthetic', 'on', true)").query(String.class).single();
                    return jdbc.sql("DELETE FROM audit_events").update();
                }))
                .isInstanceOf(DataAccessException.class);
        // признак живёт только внутри транзакции
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM suspicious_activity").update()).isInstanceOf(DataAccessException.class);
        assertThat(count("SELECT count(*) FROM suspicious_activity")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events")).isPositive();
    }
}
