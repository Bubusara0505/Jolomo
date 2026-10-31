package kg.jolomo.push;

import static kg.jolomo.push.PushFixture.endpoint;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SP11-02: opt-in, отписка, лимит, смена владельца адреса. Отказ в разрешении подписок не создаёт и ошибкой не считается. */
class PushSubscriptionServiceTest extends PostgisTestBase {

    private final MutableClock clock = new MutableClock();
    private PushSubscriptionService service;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        service = serviceWith(PushFixture.settings(true));
        alice = PushFixture.user(jdbc);
        bob = PushFixture.user(jdbc);
    }

    private PushSubscriptionService serviceWith(PushSettings settings) {
        return new PushSubscriptionService(jdbc, tx, clock, settings, new EndpointPolicy(settings.allowedHosts()));
    }

    private String code(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e.code();
        }
        throw new AssertionError("ожидалась ApiException");
    }

    @Test
    void subscribingCreatesAnActiveSubscriptionWithTheDefaultExpiry() {
        var s = service.subscribe(alice, endpoint("a1"), null);

        assertThat(service.list(alice)).containsExactly(s);
        assertThat(s.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(90)));
    }

    @Test
    void theSameEndpointAgainKeepsOneRowAndExtendsTheExpiry() {
        var first = service.subscribe(alice, endpoint("a1"), null);
        clock.advance(Duration.ofDays(10));
        var again = service.subscribe(alice, endpoint("a1"), null);

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.createdAt()).isEqualTo(first.createdAt());
        assertThat(again.expiresAt()).isAfter(first.expiresAt());
        assertThat(service.list(alice)).hasSize(1);
    }

    @Test
    void theBrowserExpiryIsUsedButCappedAndAPastOneIsRejected() {
        long in5Days = clock.instant().plus(Duration.ofDays(5)).toEpochMilli();
        long in400Days = clock.instant().plus(Duration.ofDays(400)).toEpochMilli();

        assertThat(service.subscribe(alice, endpoint("a1"), in5Days).expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(5)));
        assertThat(service.subscribe(alice, endpoint("a2"), in400Days).expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(90)));
        assertThat(code(() -> service.subscribe(alice, endpoint("a3"), clock.instant().minusSeconds(1).toEpochMilli())))
                .isEqualTo("PUSH_SUBSCRIPTION_EXPIRED");
    }

    @Test
    void badEndpointsAreRejectedAndNothingIsStored() {
        assertThat(code(() -> service.subscribe(alice, "http://fcm.googleapis.com/x", null))).isEqualTo("PUSH_ENDPOINT_INVALID");
        assertThat(code(() -> service.subscribe(alice, "https://127.0.0.1/x", null))).isEqualTo("PUSH_ENDPOINT_INVALID");
        assertThat(code(() -> service.subscribe(alice, "", null))).isEqualTo("PUSH_ENDPOINT_INVALID");

        assertThat(jdbc.sql("SELECT count(*) FROM web_push_subscriptions").query(Integer.class).single()).isZero();
    }

    @Test
    void whenPushIsNotConfiguredOptInIsRefusedButUnsubscribingStillWorks() {
        var s = service.subscribe(alice, endpoint("a1"), null);
        PushSubscriptionService off = serviceWith(PushFixture.settings(false));

        assertThat(code(() -> off.subscribe(alice, endpoint("a2"), null))).isEqualTo("PUSH_DISABLED");
        off.revoke(alice, s.id());
        assertThat(off.list(alice)).isEmpty();
    }

    @Test
    void anUnknownUserCannotSubscribe() {
        assertThat(code(() -> service.subscribe(UUID.randomUUID(), endpoint("a1"), null))).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void theTenDeviceLimitHoldsButResubscribingAnExistingOneStillWorks() {
        for (int i = 0; i < PushSubscriptionService.MAX_ACTIVE_PER_USER; i++) {
            service.subscribe(alice, endpoint("d" + i), null);
        }

        assertThat(code(() -> service.subscribe(alice, endpoint("extra"), null))).isEqualTo("PUSH_LIMIT_REACHED");
        service.subscribe(alice, endpoint("d0"), null);
        assertThat(service.list(alice)).hasSize(PushSubscriptionService.MAX_ACTIVE_PER_USER);
        // Чужой лимит не мешает.
        service.subscribe(bob, endpoint("b1"), null);
    }

    @Test
    void revokedAndExpiredSubscriptionsFreeTheLimitAndDisappearFromTheList() {
        var first = service.subscribe(alice, endpoint("d0"), clock.instant().plus(Duration.ofDays(1)).toEpochMilli());
        for (int i = 1; i < PushSubscriptionService.MAX_ACTIVE_PER_USER; i++) {
            service.subscribe(alice, endpoint("d" + i), null);
        }
        clock.advance(Duration.ofDays(2));

        assertThat(service.list(alice)).hasSize(PushSubscriptionService.MAX_ACTIVE_PER_USER - 1).noneMatch(s -> s.id().equals(first.id()));
        service.subscribe(alice, endpoint("new"), null);
    }

    @Test
    void unsubscribingByIdIsIdempotentAndHidesNothingAboutOtherPeople() {
        var s = service.subscribe(alice, endpoint("a1"), null);

        service.revoke(alice, s.id());
        service.revoke(alice, s.id());

        assertThat(service.list(alice)).isEmpty();
        assertThat(jdbc.sql("SELECT revoke_reason FROM web_push_subscriptions WHERE id = :id").param("id", s.id()).query(String.class).single())
                .isEqualTo("USER");
        assertThat(code(() -> service.revoke(bob, s.id()))).isEqualTo("PUSH_SUBSCRIPTION_NOT_FOUND");
        assertThat(code(() -> service.revoke(alice, UUID.randomUUID()))).isEqualTo("PUSH_SUBSCRIPTION_NOT_FOUND");
    }

    @Test
    void unsubscribingByEndpointWorksAndIsSilentForUnknownOrForeignAddresses() {
        service.subscribe(alice, endpoint("a1"), null);
        service.subscribe(bob, endpoint("b1"), null);

        service.revokeByEndpoint(alice, endpoint("b1"));    // чужой адрес: ничего не происходит, ответа-подсказки нет
        service.revokeByEndpoint(alice, endpoint("never"));
        assertThat(service.list(bob)).hasSize(1);

        service.revokeByEndpoint(alice, endpoint("a1"));
        assertThat(service.list(alice)).isEmpty();
    }

    @Test
    void anEndpointMovesToTheNewAccountOnTheSameDevice() {
        var old = service.subscribe(alice, endpoint("shared"), null);
        var moved = service.subscribe(bob, endpoint("shared"), null);

        assertThat(service.list(alice)).isEmpty();
        assertThat(service.list(bob)).extracting(PushSubscriptionService.Subscription::id).containsExactly(moved.id());
        // Прежний владелец не может отозвать подписку нового, у него её уже нет.
        assertThat(code(() -> service.revoke(alice, old.id()))).isEqualTo("PUSH_SUBSCRIPTION_NOT_FOUND");
        assertThat(service.list(bob)).hasSize(1);
    }

    @Test
    void resubscribingAfterAnUnsubscribeReactivatesTheAddress() {
        var s = service.subscribe(alice, endpoint("a1"), null);
        service.revoke(alice, s.id());

        service.subscribe(alice, endpoint("a1"), null);

        assertThat(service.list(alice)).hasSize(1);
        assertThat(jdbc.sql("SELECT revoke_reason FROM web_push_subscriptions").query(String.class).optional()).isEmpty();
    }
}
