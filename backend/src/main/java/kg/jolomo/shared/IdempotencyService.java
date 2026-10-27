package kg.jolomo.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Идемпотентность мутаций (RQ-06). Запись о ключе пишется в той же транзакции, что и само действие: либо есть и
 * действие, и сохранённый ответ, либо нет ни того ни другого. Тот же ключ и тот же отпечаток тела возвращают
 * сохранённый ответ (в том числе после рестарта, он лежит в базе). Другой отпечаток с тем же ключом это 409.
 * Параллельные запросы с одним ключом идут по очереди (advisory-блокировка). Отказы не сохраняются: повтор после
 * отказа заново проверяет правила. Сроки хранения записей не заданы (ждут юриста), удаления пока нет.
 */
@Component
public class IdempotencyService {

    /** Что вернуло действие: HTTP-статус и готовое тело ответа. */
    public record Outcome(int status, String body) {}

    /** Итог для вызывающего: replayed = true, если ответ взят из сохранённого. */
    public record Result(int status, String body, boolean replayed) {}

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{8,128}$");

    private record Stored(String fingerprint, int status, String body) {}

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;

    public IdempotencyService(JdbcClient jdbc, TransactionOperations tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /** Ключ обязателен: 8-128 символов из A-Z a-z 0-9 . _ - */
    public static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "error.idempotency.required");
        }
        if (!KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "error.idempotency.invalid");
        }
        return key;
    }

    /** Отпечаток тела: SHA-256 от разобранных полей, а не от сырых байтов (пробелы и порядок полей не важны). */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Result execute(UUID subject, String scope, String key, String fingerprint, Supplier<Outcome> action) {
        return tx.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))")
                    .param("k", "IDEMPOTENCY:" + subject + ":" + scope + ":" + key)
                    .query((rs, i) -> 1)
                    .list();
            Optional<Stored> existing = jdbc.sql("""
                            SELECT request_fingerprint, response_status, response_body FROM idempotency_records
                            WHERE subject_user_id = :s AND scope = :sc AND idem_key = :k
                            """)
                    .param("s", subject)
                    .param("sc", scope)
                    .param("k", key)
                    .query((rs, i) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3)))
                    .optional();
            if (existing.isPresent()) {
                if (!existing.get().fingerprint().equals(fingerprint)) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "error.idempotency.reused");
                }
                return new Result(existing.get().status(), existing.get().body(), true);
            }
            Outcome outcome = action.get();
            jdbc.sql("""
                            INSERT INTO idempotency_records
                                (id, subject_user_id, scope, idem_key, request_fingerprint, response_status, response_body, created_at)
                            VALUES (:id, :s, :sc, :k, :fp, :st, :body, :at)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("s", subject)
                    .param("sc", scope)
                    .param("k", key)
                    .param("fp", fingerprint)
                    .param("st", outcome.status())
                    .param("body", outcome.body())
                    .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                    .update();
            return new Result(outcome.status(), outcome.body(), false);
        });
    }
}
