package kg.jolomo.push;

import static kg.jolomo.push.PushFixture.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP11-02: сбой push тихий. Он ничего не подтверждает, не бросает исключений и не трогает деловые данные. */
class PushNotifierTest extends PostgisTestBase {

    private final MutableClock clock = new MutableClock();
    private final List<URI> sent = new ArrayList<>();
    private final Map<String, PushSender.Outcome> outcomes = new HashMap<>();
    private PushSender.Outcome fallback = PushSender.Outcome.ACCEPTED;
    private RuntimeException boom;
    private PushSubscriptionService subscriptions;
    private PushNotifier notifier;
    private UUID user;

    @BeforeEach
    void setUp() {
        PushSettings settings = PushFixture.settings(true);
        EndpointPolicy policy = new EndpointPolicy(settings.allowedHosts());
        subscriptions = new PushSubscriptionService(jdbc, tx, clock, settings, policy);
        notifier = notifierWith(settings);
        user = PushFixture.user(jdbc);
    }

    private PushNotifier notifierWith(PushSettings settings) {
        PushSender sender = uri -> {
            sent.add(uri);
            if (boom != null) {
                throw boom;
            }
            return outcomes.getOrDefault(uri.toString(), fallback);
        };
        return new PushNotifier(jdbc, clock, settings, new EndpointPolicy(settings.allowedHosts()), sender);
    }

    private String reason(String name) {
        return jdbc.sql("SELECT revoke_reason FROM web_push_subscriptions WHERE endpoint = :e").param("e", endpoint(name))
                .query(String.class).optional().orElse(null);
    }

    private int failures(String name) {
        return jdbc.sql("SELECT failure_count FROM web_push_subscriptions WHERE endpoint = :e").param("e", endpoint(name))
                .query(Integer.class).single();
    }

    @Test
    void everyActiveDeviceOfThePersonIsWokenAndOthersAreNot() {
        subscriptions.subscribe(user, endpoint("a1"), null);
        subscriptions.subscribe(user, endpoint("a2"), null);
        subscriptions.subscribe(PushFixture.user(jdbc), endpoint("other"), null);

        PushNotifier.Result r = notifier.notifyUser(user);

        assertThat(r).isEqualTo(new PushNotifier.Result(2, 2, 0, 0));
        assertThat(sent).extracting(URI::toString).containsExactlyInAnyOrder(endpoint("a1"), endpoint("a2"));
    }

    @Test
    void withoutASubscriptionNothingHappensAndThatIsNormal() {
        assertThat(notifier.notifyUser(user)).isEqualTo(new PushNotifier.Result(0, 0, 0, 0));
        assertThat(sent).isEmpty();
    }

    @Test
    void whenPushIsDisabledNothingIsSent() {
        subscriptions.subscribe(user, endpoint("a1"), null);
        PushNotifier off = notifierWith(PushFixture.settings(false));

        assertThat(off.notifyUser(user)).isEqualTo(new PushNotifier.Result(0, 0, 0, 0));
        assertThat(sent).isEmpty();
    }

    @Test
    void aGoneSubscriptionIsRevokedAtOnceAndNotTriedAgain() {
        subscriptions.subscribe(user, endpoint("a1"), null);
        outcomes.put(endpoint("a1"), PushSender.Outcome.GONE);

        assertThat(notifier.notifyUser(user).gone()).isEqualTo(1);
        assertThat(reason("a1")).isEqualTo("GONE");
        sent.clear();
        notifier.notifyUser(user);

        assertThat(sent).isEmpty();
    }

    @Test
    void failuresAreCountedAndTheSubscriptionIsRevokedAfterTheLimit() {
        subscriptions.subscribe(user, endpoint("a1"), null);
        fallback = PushSender.Outcome.FAILED;

        for (int i = 1; i <= 4; i++) {
            notifier.notifyUser(user);
            assertThat(failures("a1")).isEqualTo(i);
            assertThat(reason("a1")).isNull();
        }
        notifier.notifyUser(user);

        assertThat(reason("a1")).isEqualTo("REPEATED_FAILURES");
        assertThat(subscriptions.list(user)).isEmpty();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        subscriptions.subscribe(user, endpoint("a1"), null);
        fallback = PushSender.Outcome.FAILED;
        notifier.notifyUser(user);
        notifier.notifyUser(user);
        assertThat(failures("a1")).isEqualTo(2);

        fallback = PushSender.Outcome.ACCEPTED;
        notifier.notifyUser(user);

        assertThat(failures("a1")).isZero();
    }

    @Test
    void aSenderThatThrowsNeverEscapesAndCountsAsAFailure() {
        subscriptions.subscribe(user, endpoint("a1"), null);
        boom = new IllegalStateException("push service exploded");

        PushNotifier.Result r = notifier.notifyUser(user);

        assertThat(r.failed()).isEqualTo(1);
        assertThat(failures("a1")).isEqualTo(1);
    }

    @Test
    void aDatabaseProblemDoesNotEscapeEither() {
        PushNotifier broken = new PushNotifier(
                org.springframework.jdbc.core.simple.JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "x", "x")),
                clock, PushFixture.settings(true), new EndpointPolicy(List.of()), uri -> PushSender.Outcome.ACCEPTED);

        assertThat(broken.notifyUser(user)).isEqualTo(new PushNotifier.Result(0, 0, 0, 0));
    }

    @Test
    void anAddressThatFailsThePolicyNowIsNeverCalledAndIsRevoked() {
        subscriptions.subscribe(user, endpoint("a1"), null);
        // Подложенная строка, как если бы список разрешённых доменов позже сузили.
        jdbc.sql("INSERT INTO web_push_subscriptions (id, user_id, endpoint, created_at, expires_at) VALUES (:id, :u, 'http://127.0.0.1/steal', :now, :exp)")
                .param("id", UUID.randomUUID()).param("u", user)
                .param("now", java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC))
                .param("exp", java.time.OffsetDateTime.ofInstant(clock.instant().plus(Duration.ofDays(5)), java.time.ZoneOffset.UTC))
                .update();

        notifier.notifyUser(user);

        assertThat(sent).extracting(URI::toString).containsExactly(endpoint("a1"));
        assertThat(jdbc.sql("SELECT revoke_reason FROM web_push_subscriptions WHERE endpoint = 'http://127.0.0.1/steal'")
                        .query(String.class).single()).isEqualTo("POLICY");
    }

    @Test
    void expiredSubscriptionsAreSkipped() {
        subscriptions.subscribe(user, endpoint("a1"), clock.instant().plus(Duration.ofDays(1)).toEpochMilli());
        clock.advance(Duration.ofDays(2));

        assertThat(notifier.notifyUser(user).attempted()).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void aFailedPushLeavesBusinessTablesUntouched() {
        long before = jdbc.sql("SELECT (SELECT count(*) FROM bookings) + (SELECT count(*) FROM parcel_requests) + (SELECT count(*) FROM domain_events) + (SELECT count(*) FROM audit_events)")
                .query(Long.class).single();
        subscriptions.subscribe(user, endpoint("a1"), null);
        fallback = PushSender.Outcome.FAILED;

        notifier.notifyUser(user);

        long after = jdbc.sql("SELECT (SELECT count(*) FROM bookings) + (SELECT count(*) FROM parcel_requests) + (SELECT count(*) FROM domain_events) + (SELECT count(*) FROM audit_events)")
                .query(Long.class).single();
        assertThat(after).isEqualTo(before);
    }
}
