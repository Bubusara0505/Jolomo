package kg.jolomo.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kg.jolomo.events.DomainEventStore.StoredEvent;
import kg.jolomo.testsupport.MutableClock;
import kg.jolomo.testsupport.PostgisTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** SP05-04: журнал событий append-only, версии без дублей, повтор того же event id игнорируется. */
class DomainEventStoreTest extends PostgisTestBase {

    private final MutableClock clock = new MutableClock();
    private DomainEventStore store;

    @BeforeEach
    void setUp() {
        store = new DomainEventStore(jdbc, tx, clock);
    }

    @Test
    void versionsGrowByOnePerAggregateAndAreIndependentBetweenAggregates() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        store.append("TRIP", a, "TRIP_CREATED", null, Map.of());
        store.append("TRIP", a, "TRIP_UPDATED", null, Map.of());
        store.append("TRIP", b, "TRIP_CREATED", null, Map.of());
        store.append("TRIP", a, "TRIP_CANCELLED", null, Map.of());

        assertThat(store.forAggregate("TRIP", a)).extracting(StoredEvent::version).containsExactly(1, 2, 3);
        assertThat(store.forAggregate("TRIP", a)).extracting(StoredEvent::eventType)
                .containsExactly("TRIP_CREATED", "TRIP_UPDATED", "TRIP_CANCELLED");
        assertThat(store.forAggregate("TRIP", b)).extracting(StoredEvent::version).containsExactly(1);
    }

    @Test
    void repeatOfTheSameEventIdIsIgnoredAndTakesNoVersion() {
        UUID agg = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        assertThat(store.append(eventId, "TRIP", agg, "TRIP_CREATED", null, Map.of())).isTrue();
        assertThat(store.append(eventId, "TRIP", agg, "TRIP_CREATED", null, Map.of())).isFalse();
        assertThat(store.append(eventId, "TRIP", agg, "TRIP_UPDATED", null, Map.of("x", 1))).isFalse();
        assertThat(store.append("TRIP", agg, "TRIP_UPDATED", null, Map.of())).isTrue();

        assertThat(store.forAggregate("TRIP", agg)).extracting(StoredEvent::version).containsExactly(1, 2);
        assertThat(store.forAggregate("TRIP", agg).get(0).id()).isEqualTo(eventId);
    }

    @Test
    void eventsCannotBeChangedOrDeleted() {
        UUID agg = UUID.randomUUID();
        store.append("TRIP", agg, "TRIP_CREATED", null, Map.of());

        assertThatThrownBy(() -> jdbc.sql("UPDATE domain_events SET event_type = 'X'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM domain_events").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(store.forAggregate("TRIP", agg)).hasSize(1);
    }

    @Test
    void databaseRejectsADuplicateVersionEvenIfTheCodeIsBypassed() {
        UUID agg = UUID.randomUUID();
        store.append("TRIP", agg, "TRIP_CREATED", null, Map.of());

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO domain_events (id, aggregate_type, aggregate_id, version, event_type, occurred_at)
                                VALUES (gen_random_uuid(), 'TRIP', :a, 1, 'TRIP_UPDATED', now())
                                """)
                        .param("a", agg)
                        .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void payloadIsStoredAsJsonWithEscapingAndNesting() {
        UUID agg = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        store.append("TRIP", agg, "TRIP_UPDATED", actor,
                Map.of("note", "кавычки \" и \\ и перенос\nстроки", "changes", Map.of("priceKgs", Map.of("from", 700, "to", 800)), "ok", true));

        StoredEvent e = store.forAggregate("TRIP", agg).get(0);

        assertThat(e.actorUserId()).isEqualTo(actor);
        assertThat(e.occurredAt()).isEqualTo(clock.instant());
        assertThat(jdbc.sql("SELECT payload->'changes'->'priceKgs'->>'to' FROM domain_events").query(String.class).single()).isEqualTo("800");
        assertThat(jdbc.sql("SELECT payload->>'note' FROM domain_events").query(String.class).single())
                .isEqualTo("кавычки \" и \\ и перенос\nстроки");
        assertThat(jdbc.sql("SELECT (payload->>'ok')::boolean FROM domain_events").query(Boolean.class).single()).isTrue();
    }

    @Test
    void parallelAppendsToOneAggregateGetDistinctConsecutiveVersions() throws Exception {
        UUID agg = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 5; i++) {
                        store.append("TRIP", agg, "TRIP_UPDATED", null, Map.of());
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(store.forAggregate("TRIP", agg)).extracting(StoredEvent::version)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 40).boxed().toList());
    }
}
