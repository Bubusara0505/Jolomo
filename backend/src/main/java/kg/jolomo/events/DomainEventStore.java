package kg.jolomo.events;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Append-only журнал доменных событий (SP05-04). Событие пишется в транзакции изменения: нет изменения без события и
 * события без изменения. Версия событий одной сущности растёт на единицу; запись с уже известным id игнорируется и
 * версию не занимает. Изменять и удалять события нельзя (триггер базы). Персональные данные в payload не кладём.
 */
@Component
public class DomainEventStore {

    public record StoredEvent(UUID id, String aggregateType, UUID aggregateId, int version, String eventType,
                              UUID actorUserId, Instant occurredAt, String payloadJson) {}

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;

    public DomainEventStore(JdbcClient jdbc, TransactionOperations tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** Новое событие со свежим id. */
    public boolean append(String aggregateType, UUID aggregateId, String eventType, UUID actorUserId, Map<String, Object> payload) {
        return append(UUID.randomUUID(), aggregateType, aggregateId, eventType, actorUserId, payload);
    }

    /** Возвращает true, если событие записано, и false, если событие с таким id уже есть (повтор игнорируется). */
    public boolean append(UUID eventId, String aggregateType, UUID aggregateId, String eventType, UUID actorUserId,
                          Map<String, Object> payload) {
        Boolean inserted = tx.execute(status -> {
            // Запись версий одной сущности идёт строго по очереди: блокировка живёт до конца транзакции.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", aggregateType + ":" + aggregateId)
                    .query((rs, i) -> 1)
                    .list();
            int n = jdbc.sql("""
                            INSERT INTO domain_events
                                (id, aggregate_type, aggregate_id, version, event_type, actor_user_id, occurred_at, payload)
                            VALUES (:id, :type, :agg,
                                    (SELECT COALESCE(MAX(version), 0) + 1 FROM domain_events
                                     WHERE aggregate_type = :type AND aggregate_id = :agg),
                                    :event, :actor, :at, CAST(:payload AS jsonb))
                            ON CONFLICT (id) DO NOTHING
                            """)
                    .param("id", eventId)
                    .param("type", aggregateType)
                    .param("agg", aggregateId)
                    .param("event", eventType)
                    .param("actor", actorUserId)
                    .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .param("payload", Json.write(payload == null ? Map.of() : payload))
                    .update();
            return n == 1;
        });
        return Boolean.TRUE.equals(inserted);
    }

    public List<StoredEvent> forAggregate(String aggregateType, UUID aggregateId) {
        return jdbc.sql("""
                        SELECT id, aggregate_type, aggregate_id, version, event_type, actor_user_id, occurred_at, payload::text
                        FROM domain_events WHERE aggregate_type = :t AND aggregate_id = :a ORDER BY version
                        """)
                .param("t", aggregateType)
                .param("a", aggregateId)
                .query((rs, i) -> new StoredEvent(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                        rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, OffsetDateTime.class).toInstant(),
                        rs.getString(8)))
                .list();
    }
}
