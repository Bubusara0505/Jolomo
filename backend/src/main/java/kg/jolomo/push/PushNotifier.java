package kg.jolomo.push;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Будит устройства человека: «в приложении есть что посмотреть». Нагрузки нет, поэтому push не несёт контактов и не может
 * подтвердить ни бронь, ни доставку: решения принимает только сервер по событиям и кодам. Вызывать ПОСЛЕ фиксации деловой
 * транзакции. Метод никогда не бросает исключений и ничего не пишет в деловые данные: тихий сбой push остаётся тихим.
 *
 * <p>Служебная работа с подписками: 404/410 от push-службы отзывает подписку сразу, остальные сбои считаются, и после
 * {@code maxFailures} подряд подписка отзывается. Адрес подписки в журнал не пишется.
 */
@Service
public class PushNotifier {

    public record Result(int attempted, int accepted, int gone, int failed) {
        static final Result NONE = new Result(0, 0, 0, 0);
    }

    private record Row(UUID id, String endpoint) {}

    private static final Logger LOG = LoggerFactory.getLogger(PushNotifier.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final PushSettings settings;
    private final EndpointPolicy policy;
    private final PushSender sender;

    public PushNotifier(JdbcClient jdbc, Clock clock, PushSettings settings, EndpointPolicy policy, PushSender sender) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.policy = policy;
        this.sender = sender;
    }

    public Result notifyUser(UUID userId) {
        if (!settings.enabled()) {
            return Result.NONE;
        }
        try {
            List<Row> rows = jdbc.sql("""
                            SELECT id, endpoint FROM web_push_subscriptions
                            WHERE user_id = :u AND revoked_at IS NULL AND expires_at > :now
                            ORDER BY created_at, id
                            """)
                    .param("u", userId).param("now", at(clock.instant()))
                    .query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("endpoint")))
                    .list();
            int accepted = 0;
            int gone = 0;
            int failed = 0;
            for (Row row : rows) {
                PushSender.Outcome outcome = sendOne(row);
                switch (outcome) {
                    case ACCEPTED -> accepted++;
                    case GONE -> gone++;
                    case FAILED -> failed++;
                }
            }
            return new Result(rows.size(), accepted, gone, failed);
        } catch (RuntimeException e) {
            LOG.warn("Web Push не отправлен пользователю {}: {}", userId, e.getClass().getSimpleName());
            return Result.NONE;
        }
    }

    private PushSender.Outcome sendOne(Row row) {
        Optional<URI> uri = policy.check(row.endpoint());
        if (uri.isEmpty()) {
            // Список разрешённых доменов мог стать уже, чем был при подписке. Такой адрес больше не вызываем.
            revoke(row.id(), "POLICY");
            return PushSender.Outcome.GONE;
        }
        PushSender.Outcome outcome;
        try {
            outcome = sender.send(uri.get());
        } catch (RuntimeException e) {
            outcome = PushSender.Outcome.FAILED;
        }
        switch (outcome) {
            case ACCEPTED -> jdbc.sql("UPDATE web_push_subscriptions SET failure_count = 0, last_failure_at = NULL WHERE id = :id")
                    .param("id", row.id()).update();
            case GONE -> revoke(row.id(), "GONE");
            case FAILED -> recordFailure(row.id());
        }
        return outcome;
    }

    private void recordFailure(UUID id) {
        Instant now = clock.instant();
        jdbc.sql("""
                        UPDATE web_push_subscriptions SET
                            failure_count = failure_count + 1,
                            last_failure_at = :now,
                            revoked_at = CASE WHEN failure_count + 1 >= :max THEN :now ELSE revoked_at END,
                            revoke_reason = CASE WHEN failure_count + 1 >= :max THEN 'REPEATED_FAILURES' ELSE revoke_reason END
                        WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("now", at(now)).param("max", settings.maxFailures()).param("id", id)
                .update();
    }

    private void revoke(UUID id, String reason) {
        jdbc.sql("UPDATE web_push_subscriptions SET revoked_at = :now, revoke_reason = :r WHERE id = :id AND revoked_at IS NULL")
                .param("now", at(clock.instant())).param("r", reason).param("id", id)
                .update();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
