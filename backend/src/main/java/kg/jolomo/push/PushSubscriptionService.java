package kg.jolomo.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Opt-in и отписка (SP11-02). Отказ в разрешении браузера это обычное состояние: тогда подписки просто нет, и всё в приложении
 * работает так же. Отписаться можно всегда, даже если сервер сейчас не настроен на push. Адрес подписки в ответах не возвращается.
 */
@Service
public class PushSubscriptionService {

    /** Сколько устройств одного человека могут быть подписаны одновременно. */
    static final int MAX_ACTIVE_PER_USER = 10;

    public record Subscription(UUID id, Instant createdAt, Instant expiresAt) {}

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final PushSettings settings;
    private final EndpointPolicy policy;

    public PushSubscriptionService(JdbcClient jdbc, TransactionOperations tx, Clock clock, PushSettings settings, EndpointPolicy policy) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.settings = settings;
        this.policy = policy;
    }

    /**
     * Включает push на этом устройстве. Повтор с тем же адресом безопасен и продлевает срок. Если адрес уже был у другого
     * человека (на устройстве сменили аккаунт), он переходит к текущему, а старый владелец его больше не видит и не отзывает.
     *
     * @param expirationTimeMillis срок из браузера (PushSubscription.expirationTime), если он его назвал
     */
    public Subscription subscribe(UUID userId, String endpoint, Long expirationTimeMillis) {
        if (!settings.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_DISABLED", "error.push.disabled");
        }
        if (policy.check(endpoint).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PUSH_ENDPOINT_INVALID", "error.push.endpoint_invalid");
        }
        Instant now = clock.instant();
        Instant cap = now.plus(Duration.ofDays(settings.subscriptionDays()));
        Instant expires = cap;
        if (expirationTimeMillis != null) {
            Instant fromBrowser = Instant.ofEpochMilli(expirationTimeMillis);
            if (!fromBrowser.isAfter(now)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PUSH_SUBSCRIPTION_EXPIRED", "error.push.subscription_expired");
            }
            expires = fromBrowser.isBefore(cap) ? fromBrowser : cap;
        }
        Instant expiresAt = expires;
        return tx.execute(status -> {
            // Строка пользователя сериализует его подписки: лимит нельзя обойти двумя одновременными запросами.
            jdbc.sql("SELECT id FROM users WHERE id = :u FOR UPDATE").param("u", userId).query(UUID.class).optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "error.user.not_found"));
            int others = jdbc.sql("""
                            SELECT count(*) FROM web_push_subscriptions
                            WHERE user_id = :u AND revoked_at IS NULL AND expires_at > :now AND endpoint <> :e
                            """)
                    .param("u", userId).param("now", at(now)).param("e", endpoint)
                    .query(Integer.class).single();
            if (others >= MAX_ACTIVE_PER_USER) {
                throw new ApiException(HttpStatus.CONFLICT, "PUSH_LIMIT_REACHED", "error.push.limit_reached");
            }
            return jdbc.sql("""
                            INSERT INTO web_push_subscriptions (id, user_id, endpoint, created_at, expires_at)
                            VALUES (:id, :u, :e, :now, :exp)
                            ON CONFLICT (endpoint) DO UPDATE SET
                                created_at = CASE WHEN web_push_subscriptions.user_id = EXCLUDED.user_id
                                                   AND web_push_subscriptions.revoked_at IS NULL
                                                  THEN web_push_subscriptions.created_at ELSE EXCLUDED.created_at END,
                                user_id = EXCLUDED.user_id,
                                expires_at = EXCLUDED.expires_at,
                                revoked_at = NULL,
                                revoke_reason = NULL,
                                failure_count = 0,
                                last_failure_at = NULL
                            RETURNING id, created_at, expires_at
                            """)
                    .param("id", UUID.randomUUID()).param("u", userId).param("e", endpoint)
                    .param("now", at(now)).param("exp", at(expiresAt))
                    .query((rs, i) -> new Subscription(
                            rs.getObject("id", UUID.class),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                    .single();
        });
    }

    /** Действующие подписки человека (без адресов). */
    public List<Subscription> list(UUID userId) {
        return jdbc.sql("""
                        SELECT id, created_at, expires_at FROM web_push_subscriptions
                        WHERE user_id = :u AND revoked_at IS NULL AND expires_at > :now
                        ORDER BY created_at, id
                        """)
                .param("u", userId).param("now", at(clock.instant()))
                .query((rs, i) -> new Subscription(
                        rs.getObject("id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Снимает подписку по id. Повтор безопасен. Чужой или несуществующий id это 404. */
    public void revoke(UUID userId, UUID subscriptionId) {
        int changed = jdbc.sql("""
                        UPDATE web_push_subscriptions SET revoked_at = :now, revoke_reason = 'USER'
                        WHERE id = :id AND user_id = :u AND revoked_at IS NULL
                        """)
                .param("now", at(clock.instant())).param("id", subscriptionId).param("u", userId)
                .update();
        if (changed == 0) {
            boolean mine = jdbc.sql("SELECT EXISTS (SELECT 1 FROM web_push_subscriptions WHERE id = :id AND user_id = :u)")
                    .param("id", subscriptionId).param("u", userId).query(Boolean.class).single();
            if (!mine) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PUSH_SUBSCRIPTION_NOT_FOUND", "error.push.subscription_not_found");
            }
        }
    }

    /**
     * Снимает подписку по адресу: браузер знает свой адрес, а не наш id. Неизвестный или чужой адрес молча ничего не делает,
     * чтобы по ответу нельзя было проверить, чей он.
     */
    public void revokeByEndpoint(UUID userId, String endpoint) {
        jdbc.sql("""
                        UPDATE web_push_subscriptions SET revoked_at = :now, revoke_reason = 'USER'
                        WHERE endpoint = :e AND user_id = :u AND revoked_at IS NULL
                        """)
                .param("now", at(clock.instant())).param("e", endpoint).param("u", userId)
                .update();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
