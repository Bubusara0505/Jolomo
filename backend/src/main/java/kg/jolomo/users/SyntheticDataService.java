package kg.jolomo.users;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Синтетические данные (SP04-03). Пользователь становится синтетическим только явной пометкой со сроком; HTTP-маршрута
 * для пометки нет, её ставит инструмент стенда. Задание удаления трогает ТОЛЬКО просроченных синтетических пользователей.
 * Реального человека оно не удалит никогда: для реальных людей сроки хранения пусты, пока их не задаст юрист.
 *
 * <p>Что пропускается: пользователь с действующим legal hold; пользователь, связанный бронью, поездкой или посылкой с кем-то,
 * кто под удаление не попадает (иначе удалилась бы чужая строка). Связанные синтетические пользователи уходят вместе.
 * Что удаляется у каждого: файлы личности (байты и строки), заявки, сессии, профиль, поездки, брони, посылки и их проверки,
 * инциденты, пометки, подписки Web Push, ключи идемпотентности, коды и SMS на его номер, сама строка пользователя.
 * Что остаётся: audit_events и domain_events (в них только идентификаторы, внешних ключей нет), legal_holds.
 * Каждое удаление пишет строку аудита в той же транзакции.
 */
@Service
public class SyntheticDataService {

    public record PurgeResult(int deleted, int skippedLegalHold, int skippedLinked, int fileErrors) {}

    private static final Logger LOG = LoggerFactory.getLogger(SyntheticDataService.class);

    /** Пары «две стороны сделки» по бронированиям и посылкам: что связывает двух пользователей. */
    private static final String EDGE_UNION = """
            SELECT t.driver_user_id AS a, bk.passenger_user_id AS b FROM bookings bk JOIN trips t ON t.id = bk.trip_id
            UNION
            SELECT t.driver_user_id, p.sender_user_id FROM parcel_requests p JOIN trips t ON t.id = p.trip_id
            UNION
            SELECT p.driver_user_id, p.sender_user_id FROM parcel_requests p
            UNION
            SELECT p.driver_user_id, t.driver_user_id FROM parcel_requests p JOIN trips t ON t.id = p.trip_id
            """;

    private static final String PARCELS_OF_USER = """
            SELECT id FROM parcel_requests
            WHERE sender_user_id = :u OR driver_user_id = :u OR trip_id IN (SELECT id FROM trips WHERE driver_user_id = :u)
            """;

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;
    private final IdentityFileEraser files;

    public SyntheticDataService(JdbcClient jdbc, TransactionOperations tx, Clock clock, AuditLog audit, IdentityFileEraser files) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
        this.files = files;
    }

    /** Помечает пользователя синтетическим. Срок обязателен: без срока строка никогда не станет просроченной. */
    public void markSynthetic(UUID actorId, UUID userId, Instant expiresAt) {
        if (expiresAt == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYNTHETIC_EXPIRY_REQUIRED", "error.synthetic.expiry_required");
        }
        tx.executeWithoutResult(status -> {
            int changed = jdbc.sql("UPDATE users SET synthetic = true, synthetic_expires_at = :exp WHERE id = :id")
                    .param("exp", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                    .param("id", userId)
                    .update();
            if (changed == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "error.user.not_found");
            }
            audit.record(actorId, "USER_MARKED_SYNTHETIC", "USER", userId, "SYNTHETIC_DATA", null, "SYNTHETIC");
        });
    }

    public PurgeResult purgeExpired() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<UUID> expired = jdbc.sql("""
                        SELECT id FROM users
                        WHERE synthetic AND synthetic_expires_at IS NOT NULL AND synthetic_expires_at <= :now
                        ORDER BY synthetic_expires_at, id
                        """)
                .param("now", now)
                .query(UUID.class)
                .list();
        if (expired.isEmpty()) {
            return new PurgeResult(0, 0, 0, 0);
        }
        Set<UUID> held = new HashSet<>(jdbc.sql("SELECT user_id FROM legal_holds WHERE released_at IS NULL AND user_id IN (:ids)")
                .param("ids", expired)
                .query(UUID.class)
                .list());
        Set<UUID> candidates = new LinkedHashSet<>(expired);
        candidates.removeAll(held);
        int skippedHold = expired.size() - candidates.size();

        Set<UUID> allowed = closedGroup(candidates);
        int skippedLinked = candidates.size() - allowed.size();

        int deleted = 0;
        int skippedLate = 0;
        int fileErrors = 0;
        for (UUID userId : allowed) {
            List<UUID> objects = purgeOne(userId, allowed);
            if (objects == null) {
                skippedLate++;
                continue;
            }
            deleted++;
            for (UUID object : objects) {
                try {
                    files.erase(object);
                } catch (RuntimeException e) {
                    fileErrors++;
                    LOG.error("Не удалось стереть файл личности {} синтетического пользователя", object, e);
                }
            }
        }
        return new PurgeResult(deleted, skippedHold, skippedLinked + skippedLate, fileErrors);
    }

    /** Оставляет только тех, у кого все контрагенты тоже в группе. Повторяет, пока состав меняется. */
    private Set<UUID> closedGroup(Set<UUID> candidates) {
        Set<UUID> group = new LinkedHashSet<>(candidates);
        if (group.isEmpty()) {
            return group;
        }
        List<UUID[]> edges = jdbc.sql("SELECT a, b FROM (" + EDGE_UNION + ") e WHERE a IN (:ids) OR b IN (:ids)")
                .param("ids", new ArrayList<>(group))
                .query((rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
                .list();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (UUID[] e : edges) {
                boolean a = group.contains(e[0]);
                boolean b = group.contains(e[1]);
                if (a && !b) {
                    group.remove(e[0]);
                    changed = true;
                } else if (b && !a) {
                    group.remove(e[1]);
                    changed = true;
                }
            }
        }
        return group;
    }

    /**
     * Удаляет одного пользователя в своей транзакции. Возвращает id его файлов личности (стираются после фиксации)
     * или null, если к моменту удаления условия перестали выполняться (hold поставлен, появилась чужая связь).
     */
    List<UUID> purgeOne(UUID userId, Set<UUID> allowed) {
        return tx.execute(status -> {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            String phone = jdbc.sql("""
                            SELECT phone FROM users
                            WHERE id = :u AND synthetic AND synthetic_expires_at IS NOT NULL AND synthetic_expires_at <= :now
                            FOR UPDATE
                            """)
                    .param("u", userId)
                    .param("now", now)
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (phone == null) {
                return null;
            }
            boolean held = jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_holds WHERE user_id = :u AND released_at IS NULL)")
                    .param("u", userId)
                    .query(Boolean.class)
                    .single();
            if (held) {
                return null;
            }
            List<UUID> others = jdbc.sql("SELECT CASE WHEN a = :u THEN b ELSE a END FROM (" + EDGE_UNION + ") e WHERE a = :u OR b = :u")
                    .param("u", userId)
                    .query(UUID.class)
                    .list();
            if (!allowed.containsAll(others)) {
                return null;
            }
            // Исключение из append-only действует только в этой транзакции и только для incidents и suspicious_activity.
            jdbc.sql("SELECT set_config('jolomo.purge_synthetic', 'on', true)").query(String.class).single();

            List<UUID> objects = jdbc.sql("SELECT id FROM identity_objects WHERE owner_user_id = :u AND state = 'STORED'")
                    .param("u", userId)
                    .query(UUID.class)
                    .list();
            exec("DELETE FROM identity_access_grants WHERE object_id IN (SELECT id FROM identity_objects WHERE owner_user_id = :u)", userId);
            exec("DELETE FROM identity_objects WHERE owner_user_id = :u", userId);
            exec("DELETE FROM verification_cases WHERE driver_user_id = :u", userId);
            exec("DELETE FROM delivery_challenges WHERE parcel_id IN (" + PARCELS_OF_USER + ")", userId);
            exec("DELETE FROM incidents WHERE parcel_id IN (" + PARCELS_OF_USER + ")", userId);
            exec("DELETE FROM parcel_requests WHERE id IN (" + PARCELS_OF_USER + ")", userId);
            exec("DELETE FROM bookings WHERE passenger_user_id = :u OR trip_id IN (SELECT id FROM trips WHERE driver_user_id = :u)", userId);
            exec("DELETE FROM trips WHERE driver_user_id = :u", userId);
            exec("DELETE FROM idempotency_records WHERE subject_user_id = :u", userId);
            exec("DELETE FROM refresh_sessions WHERE user_id = :u", userId);
            exec("DELETE FROM suspicious_activity WHERE user_id = :u", userId);
            exec("DELETE FROM web_push_subscriptions WHERE user_id = :u", userId);
            exec("DELETE FROM driver_profiles WHERE user_id = :u", userId);
            jdbc.sql("DELETE FROM auth_challenges WHERE phone = :p").param("p", phone).update();
            jdbc.sql("DELETE FROM sms_messages WHERE phone = :p").param("p", phone).update();
            exec("DELETE FROM users WHERE id = :u", userId);

            audit.record(null, "SYNTHETIC_USER_PURGED", "USER", userId, "SYNTHETIC_EXPIRED", "SYNTHETIC", "DELETED");
            return objects;
        });
    }

    private void exec(String sql, UUID userId) {
        jdbc.sql(sql).param("u", userId).update();
    }
}
