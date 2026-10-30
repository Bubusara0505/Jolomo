package kg.jolomo.events;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Ночная выгрузка для анализа пилота (SP12-01). В выгрузку попадают метрики и события за сутки, но не персональные данные:
 * <ul>
 *   <li>идентификаторы людей и сущностей заменены псевдонимами (HMAC с ключом выгрузки);</li>
 *   <li>из payload берутся только поля из белого списка {@link EventRegistry}, всё остальное (телефоны, коды, текст,
 *       суммы изменений) отбрасывается, а значение, похожее на телефон, отбрасывается даже в разрешённом поле;</li>
 *   <li>события незарегистрированных типов не выгружаются и только считаются в skippedUnregistered;</li>
 *   <li>кодов OTP и содержимого документов в журнале событий нет, а белый список их и не пропустил бы.</li>
 * </ul>
 * Метрики считаются по когорте «все запросы до конца окна», события берутся за окно [from, to).
 */
@Service
public class ExportService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Bishkek");

    private static final Pattern PHONE_LIKE = Pattern.compile("\\+?\\d{9,}");

    private record Raw(UUID id, String aggregateType, UUID aggregateId, int version, String eventType, UUID actor,
                       Instant at, List<String> idValues, List<String> safeValues) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final MetricsService metrics;
    private final ExportSettings settings;

    public ExportService(JdbcClient jdbc, Clock clock, MetricsService metrics, ExportSettings settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.metrics = metrics;
        this.settings = settings;
    }

    /** Документ выгрузки (JSON) за окно событий [from, to). */
    public String buildDocument(Instant from, Instant to) {
        String key = settings.pseudonymKey();
        if (key == null || key.length() < 16) {
            throw new IllegalStateException("EXPORT_PSEUDONYM_KEY не задан или короче 16 символов");
        }
        Pseudonymizer pseudo = new Pseudonymizer(key);

        List<Map<String, Object>> events = new ArrayList<>();
        int skipped = 0;
        for (Raw r : readEvents(from, to)) {
            if (!EventRegistry.known(r.eventType())) {
                skipped++;
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            for (int i = 0; i < EventRegistry.ID_FIELDS.size(); i++) {
                String v = r.idValues().get(i);
                if (v != null) {
                    try {
                        data.put(EventRegistry.ID_FIELDS.get(i), pseudo.of(UUID.fromString(v)));
                    } catch (IllegalArgumentException notAnId) {
                        // Не идентификатор: не выгружаем.
                    }
                }
            }
            for (int i = 0; i < EventRegistry.SAFE_FIELDS.size(); i++) {
                String v = r.safeValues().get(i);
                if (v != null && v.length() <= 64 && !PHONE_LIKE.matcher(v).find()) {
                    data.put(EventRegistry.SAFE_FIELDS.get(i), "seats".equals(EventRegistry.SAFE_FIELDS.get(i)) ? number(v) : v);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id());
            row.put("type", r.eventType());
            row.put("aggregateType", r.aggregateType());
            row.put("aggregate", pseudo.of(r.aggregateId()));
            row.put("version", r.version());
            row.put("actor", r.actor() == null ? null : pseudo.of(r.actor()));
            row.put("occurredAt", r.at());
            row.put("data", data);
            events.add(row);
        }

        List<Map<String, Object>> metricRows = new ArrayList<>();
        for (MetricsService.Metric m : metrics.compute(Instant.EPOCH, to)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", m.code());
            row.put("numerator", m.numerator());
            row.put("denominator", m.denominator());
            row.put("value", m.value());
            metricRows.add(row);
        }

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", from);
        window.put("to", to);
        Map<String, Object> cohort = new LinkedHashMap<>();
        cohort.put("from", Instant.EPOCH);
        cohort.put("to", to);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("generatedAt", clock.instant());
        doc.put("window", window);
        doc.put("metricsCohort", cohort);
        doc.put("metrics", metricRows);
        doc.put("skippedUnregistered", skipped);
        doc.put("events", events);
        return Json.write(doc);
    }

    /** Выгрузка за календарные сутки по Asia/Bishkek в файл jolomo-export-ГГГГ-ММ-ДД.json. Возвращает путь файла. */
    public Path writeFor(LocalDate day) throws IOException {
        Instant from = day.atStartOfDay(ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZONE).toInstant();
        byte[] bytes = buildDocument(from, to).getBytes(StandardCharsets.UTF_8);

        Path dir = settings.dir();
        Files.createDirectories(dir);
        Path target = dir.resolve("jolomo-export-" + day + ".json");
        Path tmp = Files.createTempFile(dir, "export-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException notPosix) {
                // Не POSIX-система: права остаются по умолчанию.
            }
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    private List<Raw> readEvents(Instant from, Instant to) {
        StringBuilder columns = new StringBuilder();
        for (String f : EventRegistry.ID_FIELDS) {
            columns.append(", payload->>'").append(f).append("'");
        }
        for (String f : EventRegistry.SAFE_FIELDS) {
            columns.append(", payload->>'").append(f).append("'");
        }
        String sql = "SELECT id, aggregate_type, aggregate_id, version, event_type, actor_user_id, occurred_at" + columns
                + " FROM domain_events WHERE occurred_at >= :from AND occurred_at < :to"
                + " ORDER BY occurred_at, aggregate_type, aggregate_id, version";
        int idCount = EventRegistry.ID_FIELDS.size();
        int safeCount = EventRegistry.SAFE_FIELDS.size();
        return jdbc.sql(sql)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query((rs, n) -> {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < idCount; i++) {
                        ids.add(rs.getString(8 + i));
                    }
                    List<String> safe = new ArrayList<>();
                    for (int i = 0; i < safeCount; i++) {
                        safe.add(rs.getString(8 + idCount + i));
                    }
                    return new Raw(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getInt(4),
                            rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, OffsetDateTime.class).toInstant(),
                            ids, safe);
                })
                .list();
    }

    private static Object number(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }
}
