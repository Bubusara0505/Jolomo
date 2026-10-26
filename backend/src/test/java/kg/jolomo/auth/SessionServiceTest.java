package kg.jolomo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import kg.jolomo.shared.ApiException;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.Test;

/** SP02-04: access 15 минут, ротация refresh, повтор отзывает семейство. */
class SessionServiceTest extends PostgisTestBase {

    private static final String PHONE = "+996555000000";

    private final MutableClock clock = new MutableClock();
    private final SessionSettings settings =
            new SessionSettings("test-session-secret-0123456789abcdef", Duration.ofMinutes(15), Duration.ofDays(30), true);

    private SessionService service() {
        return new SessionService(jdbc, tx, clock, settings);
    }

    @Test
    void firstLoginCreatesUserAndNextLoginReusesIt() {
        SessionService s = service();
        SessionService.Tokens first = s.startSession(PHONE);
        SessionService.Tokens second = s.startSession(PHONE);

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(jdbc.sql("SELECT count(*) FROM users").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void accessTokenWorksForFifteenMinutesOnly() {
        SessionService s = service();
        SessionService.Tokens t = s.startSession(PHONE);

        assertThat(s.authenticate(t.access())).isPresent();
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(s.authenticate(t.access())).isEmpty();
    }

    @Test
    void tamperedOrGarbageAccessTokenIsRejected() {
        SessionService s = service();
        String access = s.startSession(PHONE).access();
        String tampered = access.substring(0, access.length() - 2) + (access.endsWith("AA") ? "BB" : "AA");

        assertThat(s.authenticate(tampered)).isEmpty();
        assertThat(s.authenticate("garbage")).isEmpty();
        assertThat(s.authenticate("a.b")).isEmpty();
        assertThat(s.authenticate(null)).isEmpty();
    }

    @Test
    void refreshRotatesAndNewPairWorks() {
        SessionService s = service();
        SessionService.Tokens t = s.startSession(PHONE);

        SessionService.RefreshResult r = s.refresh(t.refresh());

        assertThat(r.outcome()).isEqualTo(SessionService.RefreshOutcome.OK);
        assertThat(r.tokens().refresh()).isNotEqualTo(t.refresh());
        assertThat(s.authenticate(r.tokens().access())).isPresent();
        assertThat(s.refresh(r.tokens().refresh()).outcome()).isEqualTo(SessionService.RefreshOutcome.OK);
    }

    @Test
    void reusingRotatedRefreshRevokesTheWholeFamily() {
        SessionService s = service();
        SessionService.Tokens t = s.startSession(PHONE);
        SessionService.Tokens rotated = s.refresh(t.refresh()).tokens();

        assertThat(s.refresh(t.refresh()).outcome()).isEqualTo(SessionService.RefreshOutcome.REUSED);

        // Семейство мертво целиком: и новый refresh, и новый access.
        assertThat(s.refresh(rotated.refresh()).outcome()).isEqualTo(SessionService.RefreshOutcome.INVALID);
        assertThat(s.authenticate(rotated.access())).isEmpty();
    }

    @Test
    void logoutRevokesFamilyAndAccess() {
        SessionService s = service();
        SessionService.Tokens t = s.startSession(PHONE);

        s.logout(t.refresh());

        assertThat(s.authenticate(t.access())).isEmpty();
        assertThat(s.refresh(t.refresh()).outcome()).isEqualTo(SessionService.RefreshOutcome.INVALID);
        s.logout("unknown-token"); // не падает
    }

    @Test
    void expiredRefreshIsRejected() {
        SessionService s = service();
        SessionService.Tokens t = s.startSession(PHONE);

        clock.advance(Duration.ofDays(30).plusSeconds(1));

        assertThat(s.refresh(t.refresh()).outcome()).isEqualTo(SessionService.RefreshOutcome.INVALID);
    }

    @Test
    void suspendedUserCannotLoginOrRefresh() {
        SessionService s = service();
        SessionService.Tokens t = s.startSession(PHONE);
        jdbc.sql("UPDATE users SET status = 'SUSPENDED'").update();

        assertThat(s.refresh(t.refresh()).outcome()).isEqualTo(SessionService.RefreshOutcome.INVALID);
        assertThat(s.authenticate(t.access())).isEmpty();
        assertThatThrownBy(() -> s.startSession(PHONE))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("USER_SUSPENDED"));
    }

    @Test
    void refreshTokenIsNotStoredInTheClear() {
        SessionService.Tokens t = service().startSession(PHONE);

        String stored = jdbc.sql("SELECT token_hash FROM refresh_sessions").query(String.class).single();

        assertThat(stored).isNotEqualTo(t.refresh()).doesNotContain(t.refresh()).hasSize(64);
    }
}
