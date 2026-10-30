package kg.jolomo.parcels;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import kg.jolomo.events.DomainEventStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** Журнал событий, который «падает» на заданном типе события: имитация сбоя процесса посреди транзакции. */
final class FlakyDomainEventStore extends DomainEventStore {

    volatile String failOnType;

    FlakyDomainEventStore(JdbcClient jdbc, TransactionOperations tx, Clock clock) {
        super(jdbc, tx, clock);
    }

    @Override
    public boolean append(UUID eventId, String aggregateType, UUID aggregateId, String eventType, UUID actorUserId,
                          Map<String, Object> payload) {
        if (eventType.equals(failOnType)) {
            throw new IllegalStateException("simulated crash on " + eventType);
        }
        return super.append(eventId, aggregateType, aggregateId, eventType, actorUserId, payload);
    }
}
