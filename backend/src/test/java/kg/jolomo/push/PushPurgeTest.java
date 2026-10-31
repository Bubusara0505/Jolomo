package kg.jolomo.push;

import static kg.jolomo.push.PushFixture.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.shared.AuditLog;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import kg.jolomo.users.SyntheticDataService;
import org.junit.jupiter.api.Test;

/** SP11-02: подписки синтетического пользователя уходят вместе с ним, подписки реальных людей остаются. */
class PushPurgeTest extends PostgisTestBase {

    private final MutableClock clock = new MutableClock();

    @Test
    void subscriptionsOfAnExpiredSyntheticUserAreDeletedWithHim() {
        PushSettings settings = PushFixture.settings(true);
        var subscriptions = new PushSubscriptionService(jdbc, tx, clock, settings, new EndpointPolicy(settings.allowedHosts()));
        var synthetic = new SyntheticDataService(jdbc, tx, clock, new AuditLog(jdbc, clock), objectId -> {});
        UUID fake = PushFixture.user(jdbc);
        UUID real = PushFixture.user(jdbc);
        subscriptions.subscribe(fake, endpoint("fake1"), null);
        subscriptions.subscribe(fake, endpoint("fake2"), null);
        subscriptions.subscribe(real, endpoint("real1"), null);
        synthetic.markSynthetic(UUID.randomUUID(), fake, clock.instant().plus(Duration.ofHours(1)));
        clock.advance(Duration.ofHours(2));

        var result = synthetic.purgeExpired();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM web_push_subscriptions WHERE user_id = :u").param("u", fake).query(Integer.class).single()).isZero();
        assertThat(subscriptions.list(real)).hasSize(1);
    }
}
