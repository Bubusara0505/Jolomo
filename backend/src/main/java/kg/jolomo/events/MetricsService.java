package kg.jolomo.events;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Метрики пилота (SP12-01): у каждой явные числитель и знаменатель, как записано в docs/pilot-metrics-and-export.md.
 * Источник только журнал domain_events; считаем по уникальным сущностям. Когорта: сущности, у которых событие входа
 * (BOOKING_REQUESTED или PARCEL_REQUESTED) попало в отчётный период; их исходы считаются в любое время после входа.
 * Если знаменатель равен нулю, метрика не определена (value() = null), а не 0 % и не 100 %.
 */
@Service
public class MetricsService {

    /** Итог по одной метрике. */
    public record Metric(String code, long numerator, long denominator) {

        public boolean defined() {
            return denominator > 0;
        }

        /** Доля от 0 до 1 или null, если знаменатель равен нулю. */
        public Double value() {
            return denominator == 0 ? null : (double) numerator / denominator;
        }
    }

    private record Def(String code, String aggregate, String entryEvent, List<String> numerator, String numeratorFromState,
                       List<String> denominator) {}

    private static final List<String> BOOKING_MARKED = List.of("BOOKING_COMPLETED", "BOOKING_NO_SHOW");
    private static final List<Def> DEFS = List.of(
            new Def("BOOKING_ACCEPTANCE_RATE", "BOOKING", "BOOKING_REQUESTED",
                    List.of("BOOKING_ACCEPTED"), null, List.of("BOOKING_ACCEPTED", "BOOKING_REJECTED")),
            new Def("BOOKING_COMPLETION_RATE", "BOOKING", "BOOKING_REQUESTED",
                    List.of("BOOKING_COMPLETED"), null, BOOKING_MARKED),
            new Def("BOOKING_NO_SHOW_RATE", "BOOKING", "BOOKING_REQUESTED",
                    List.of("BOOKING_NO_SHOW"), null, BOOKING_MARKED),
            new Def("BOOKING_CANCEL_AFTER_ACCEPT_RATE", "BOOKING", "BOOKING_REQUESTED",
                    List.of("BOOKING_CANCELLED"), "ACCEPTED", List.of("BOOKING_ACCEPTED")),
            new Def("PARCEL_ACCEPTANCE_RATE", "PARCEL", "PARCEL_REQUESTED",
                    List.of("PARCEL_ACCEPTED"), null, List.of("PARCEL_ACCEPTED", "PARCEL_REJECTED")),
            new Def("PARCEL_DELIVERY_RATE", "PARCEL", "PARCEL_REQUESTED",
                    List.of("PARCEL_DELIVERED"), null, List.of("PARCEL_DELIVERY_PENDING")),
            new Def("PARCEL_VERIFICATION_LOCK_RATE", "PARCEL", "PARCEL_REQUESTED",
                    List.of("PARCEL_VERIFICATION_LOCKED"), null, List.of("PARCEL_DELIVERY_PENDING")));

    private final JdbcClient jdbc;

    public MetricsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Коды всех метрик по порядку. */
    public static List<String> codes() {
        return DEFS.stream().map(Def::code).toList();
    }

    /** Все типы событий, которые читают определения метрик (входа в когорту тоже). */
    public static Set<String> eventTypesUsed() {
        Set<String> used = new LinkedHashSet<>();
        for (Def d : DEFS) {
            used.add(d.entryEvent());
            used.addAll(d.numerator());
            used.addAll(d.denominator());
        }
        return used;
    }

    /** Считает все метрики для когорты запросов с from (включительно) по to (не включая). */
    public List<Metric> compute(Instant from, Instant to) {
        List<Metric> result = new ArrayList<>();
        for (Def d : DEFS) {
            long numerator = count(d.aggregate(), d.entryEvent(), d.numerator(), d.numeratorFromState(), from, to);
            long denominator = count(d.aggregate(), d.entryEvent(), d.denominator(), null, from, to);
            result.add(new Metric(d.code(), numerator, denominator));
        }
        return result;
    }

    private long count(String aggregate, String entryEvent, List<String> eventTypes, String fromState, Instant from, Instant to) {
        String sql = """
                SELECT count(DISTINCT e.aggregate_id) FROM domain_events e
                WHERE e.aggregate_type = :agg AND e.event_type IN (:types)
                """
                + (fromState == null ? "" : " AND e.payload->>'from' = :fromState")
                + """
                  AND e.aggregate_id IN (
                      SELECT c.aggregate_id FROM domain_events c
                      WHERE c.aggregate_type = :agg AND c.event_type = :entry AND c.occurred_at >= :from AND c.occurred_at < :to)
                """;
        JdbcClient.StatementSpec spec = jdbc.sql(sql)
                .param("agg", aggregate)
                .param("types", eventTypes)
                .param("entry", entryEvent)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        if (fromState != null) {
            spec = spec.param("fromState", fromState);
        }
        return spec.query(Long.class).single();
    }
}
