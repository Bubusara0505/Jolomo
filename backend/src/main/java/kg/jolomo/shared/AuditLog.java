package kg.jolomo.shared;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Строка аудита пишется в той же транзакции, что и действие. Персональных данных в ней нет. */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(
            UUID actorUserId, String action, String subjectType, UUID subjectId, String reasonCode, String from, String to) {
        jdbc.sql("""
                        INSERT INTO audit_events
                            (id, occurred_at, actor_user_id, action, subject_type, subject_id, reason_code, from_state, to_state)
                        VALUES (:id, :at, :actor, :action, :st, :sid, :reason, :from, :to)
                        """)
                .param("id", UUID.randomUUID())
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("actor", actorUserId)
                .param("action", action)
                .param("st", subjectType)
                .param("sid", subjectId)
                .param("reason", reasonCode)
                .param("from", from)
                .param("to", to)
                .update();
    }
}
