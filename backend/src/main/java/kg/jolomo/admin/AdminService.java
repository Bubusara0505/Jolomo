package kg.jolomo.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import kg.jolomo.shared.AesGcmBox;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.AuditLog;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Администраторы (SP04-01): пароль и обязательный TOTP, блокировка при переборе, одноразовые коды восстановления,
 * свои сессии, отзыв и сброс. Любое привилегированное действие имеет код причины и строку аудита.
 * Неудачный вход возвращается пустым результатом (а не исключением), чтобы счётчик попыток и аудит сохранились.
 */
@Service
public class AdminService {

    public record AdminPrincipal(UUID id, String login, String displayName, AdminRole role) {}

    /** Секреты показываются один раз, при создании или сбросе. В базе остаются только хеш и шифртекст. */
    public record Enrollment(
            UUID adminId, String login, String password, String totpSecret, String otpauthUri, List<String> recoveryCodes) {}

    public record LoginSession(AdminPrincipal admin, String token, Duration ttl, int recoveryCodesLeft) {}

    static final int RECOVERY_CODES = 8;
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final Pattern LOGIN = Pattern.compile("^[a-z0-9._-]{3,64}$");
    private static final Pattern REASON = Pattern.compile("^[A-Z0-9_]{3,64}$");
    private static final Pattern RECOVERY_INPUT = Pattern.compile("^[A-Z2-7]{16}$");

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Clock clock;
    private final AuditLog audit;
    private final PasswordHasher hasher;
    private final AesGcmBox box;
    private final AdminSettings settings;
    private final SecureRandom random = new SecureRandom();

    public AdminService(
            JdbcClient jdbc,
            TransactionOperations tx,
            Clock clock,
            AuditLog audit,
            PasswordHasher hasher,
            AesGcmBox box,
            AdminSettings settings) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.audit = audit;
        this.hasher = hasher;
        this.box = box;
        this.settings = settings;
    }

    // ---------- создание, сброс, отзыв ----------

    public Enrollment create(String login, String displayName, AdminRole role, UUID actorId, String reasonCode) {
        requireReason(reasonCode);
        if (login == null || !LOGIN.matcher(login).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LOGIN", "error.admin.invalidLogin");
        }
        try {
            return tx.execute(status -> {
                UUID id = UUID.randomUUID();
                Creds c = newCreds(id);
                Timestamp t = ts();
                jdbc.sql("""
                                INSERT INTO admin_accounts
                                    (id, login, display_name, role, status, password_hash, totp_secret_enc,
                                     created_at, created_by, updated_at)
                                VALUES (:id, :login, :name, :role, 'ACTIVE', :hash, :secret, :now, :by, :now)
                                """)
                        .param("id", id)
                        .param("login", login)
                        .param("name", displayName)
                        .param("role", role.name())
                        .param("hash", hasher.hash(c.password()))
                        .param("secret", c.sealedSecret())
                        .param("now", t.value())
                        .param("by", actorId)
                        .update();
                storeRecoveryCodes(id, c.recoveryCodes());
                audit.record(actorId, "ADMIN_CREATED", "ADMIN", id, reasonCode, null, role.name());
                return enrollment(id, login, c);
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_EXISTS", "error.admin.exists");
        }
    }

    /** Новый пароль, новый секрет TOTP, новые коды восстановления; старые сессии и коды перестают действовать. */
    public Enrollment reset(UUID actorId, UUID targetId, String reasonCode) {
        requireReason(reasonCode);
        requireNotSelf(actorId, targetId);
        return tx.execute(status -> {
            String login = loginOf(targetId);
            Creds c = newCreds(targetId);
            jdbc.sql("""
                            UPDATE admin_accounts
                            SET password_hash = :hash, totp_secret_enc = :secret, last_totp_step = 0,
                                failed_attempts = 0, locked_until = NULL, status = 'ACTIVE', updated_at = :now
                            WHERE id = :id
                            """)
                    .param("hash", hasher.hash(c.password()))
                    .param("secret", c.sealedSecret())
                    .param("now", ts().value())
                    .param("id", targetId)
                    .update();
            jdbc.sql("DELETE FROM admin_recovery_codes WHERE admin_id = :id").param("id", targetId).update();
            storeRecoveryCodes(targetId, c.recoveryCodes());
            revokeAllSessions(targetId, null);
            audit.record(actorId, "ADMIN_RESET", "ADMIN", targetId, reasonCode, null, null);
            return enrollment(targetId, login, c);
        });
    }

    public void disable(UUID actorId, UUID targetId, String reasonCode) {
        requireReason(reasonCode);
        requireNotSelf(actorId, targetId);
        tx.executeWithoutResult(status -> {
            loginOf(targetId);
            jdbc.sql("UPDATE admin_accounts SET status = 'DISABLED', updated_at = :now WHERE id = :id")
                    .param("now", ts().value())
                    .param("id", targetId)
                    .update();
            revokeAllSessions(targetId, null);
            audit.record(actorId, "ADMIN_DISABLED", "ADMIN", targetId, reasonCode, "ACTIVE", "DISABLED");
        });
    }

    // ---------- вход и сессии ----------

    /** @return сессия, либо пусто при любой ошибке (снаружи причина не различается). */
    public Optional<LoginSession> login(String login, String password, String code) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            Optional<Row> found = jdbc.sql("""
                            SELECT id, login, display_name, role, status, password_hash, totp_secret_enc,
                                   last_totp_step, failed_attempts, locked_until
                            FROM admin_accounts WHERE login = :l FOR UPDATE
                            """)
                    .param("l", login == null ? "" : login)
                    .query((rs, i) -> new Row(
                            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), AdminRole.valueOf(rs.getString(4)),
                            rs.getString(5), rs.getString(6), rs.getBytes(7), rs.getLong(8), rs.getInt(9),
                            rs.getObject(10, OffsetDateTime.class)))
                    .optional();
            String pw = password == null ? "" : password;
            if (found.isEmpty()) {
                hasher.verifyDummy(pw);
                audit.record(null, "ADMIN_LOGIN_FAILED", "ADMIN", null, "UNKNOWN_LOGIN", null, null);
                return Optional.empty();
            }
            Row a = found.get();
            if (!"ACTIVE".equals(a.status())) {
                hasher.verifyDummy(pw);
                audit.record(null, "ADMIN_LOGIN_FAILED", "ADMIN", a.id(), "ACCOUNT_DISABLED", null, null);
                return Optional.empty();
            }
            if (a.lockedUntil() != null && now.isBefore(a.lockedUntil().toInstant())) {
                hasher.verifyDummy(pw);
                audit.record(null, "ADMIN_LOGIN_FAILED", "ADMIN", a.id(), "LOCKED", null, null);
                return Optional.empty();
            }

            boolean passwordOk = hasher.verify(pw, a.passwordHash());
            long usedStep = -1;
            boolean recoveryUsed = false;
            if (passwordOk) {
                byte[] secret = box.open(a.secretEnc(), a.id().toString());
                usedStep = Totp.verify(secret, code, now.getEpochSecond(), a.lastStep());
                if (usedStep < 0) {
                    recoveryUsed = consumeRecoveryCode(a.id(), code, now);
                }
            }
            if (!(passwordOk && (usedStep >= 0 || recoveryUsed))) {
                registerFailure(a, passwordOk, now);
                return Optional.empty();
            }

            jdbc.sql("""
                            UPDATE admin_accounts
                            SET failed_attempts = 0, locked_until = NULL,
                                last_totp_step = GREATEST(last_totp_step, :step), updated_at = :now
                            WHERE id = :id
                            """)
                    .param("step", Math.max(usedStep, 0))
                    .param("now", ts().value())
                    .param("id", a.id())
                    .update();
            String token = randomToken();
            jdbc.sql("""
                            INSERT INTO admin_sessions (id, admin_id, token_hash, created_at, expires_at)
                            VALUES (:id, :a, :h, :now, :exp)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("a", a.id())
                    .param("h", sha256(token))
                    .param("now", ts().value())
                    .param("exp", OffsetDateTime.ofInstant(now.plus(settings.sessionTtl()), ZoneOffset.UTC))
                    .update();
            audit.record(a.id(), "ADMIN_LOGIN", "ADMIN", a.id(), recoveryUsed ? "RECOVERY_CODE" : "TOTP", null, null);
            AdminPrincipal principal = new AdminPrincipal(a.id(), a.login(), a.displayName(), a.role());
            return Optional.of(new LoginSession(principal, token, settings.sessionTtl(), recoveryCodesLeft(a.id())));
        });
    }

    public Optional<AdminPrincipal> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT a.id, a.login, a.display_name, a.role
                        FROM admin_sessions s JOIN admin_accounts a ON a.id = s.admin_id
                        WHERE s.token_hash = :h AND s.revoked_at IS NULL AND s.expires_at > :now AND a.status = 'ACTIVE'
                        """)
                .param("h", sha256(token))
                .param("now", ts().value())
                .query((rs, i) -> new AdminPrincipal(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), AdminRole.valueOf(rs.getString(4))))
                .optional();
    }

    public void logout(AdminPrincipal admin, String token) {
        jdbc.sql("UPDATE admin_sessions SET revoked_at = :now WHERE token_hash = :h AND revoked_at IS NULL")
                .param("now", ts().value())
                .param("h", sha256(token))
                .update();
        audit.record(admin.id(), "ADMIN_LOGOUT", "ADMIN", admin.id(), "SELF_SERVICE", null, null);
    }

    /** Смена своего пароля: остальные сессии этого администратора отзываются. */
    public void changePassword(AdminPrincipal admin, String currentToken, String current, String next) {
        if (next == null || next.length() < 12 || next.equals(current)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_WEAK", "error.admin.passwordWeak");
        }
        tx.executeWithoutResult(status -> {
            String hash = jdbc.sql("SELECT password_hash FROM admin_accounts WHERE id = :id FOR UPDATE")
                    .param("id", admin.id())
                    .query(String.class)
                    .single();
            if (current == null || !hasher.verify(current, hash)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_PASSWORD_WRONG", "error.admin.passwordWrong");
            }
            jdbc.sql("UPDATE admin_accounts SET password_hash = :h, updated_at = :now WHERE id = :id")
                    .param("h", hasher.hash(next))
                    .param("now", ts().value())
                    .param("id", admin.id())
                    .update();
            revokeAllSessions(admin.id(), sha256(currentToken));
            audit.record(admin.id(), "ADMIN_PASSWORD_CHANGED", "ADMIN", admin.id(), "SELF_SERVICE", null, null);
        });
    }

    /** Отказ в доступе тоже оставляет след. */
    public void recordDenied(AdminPrincipal admin, AdminPermission permission) {
        audit.record(admin.id(), "ADMIN_ACCESS_DENIED", "ADMIN", admin.id(), "NO_PERMISSION", null, permission.name());
    }

    public boolean anyAdminExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM admin_accounts)").query(Boolean.class).single();
    }

    // ---------- внутреннее ----------

    private void registerFailure(Row a, boolean passwordOk, Instant now) {
        int failures = a.failedAttempts() + 1;
        OffsetDateTime lockUntil = null;
        if (failures >= settings.maxFailures()) {
            lockUntil = OffsetDateTime.ofInstant(now.plus(settings.lockDuration()), ZoneOffset.UTC);
            failures = 0;
        }
        jdbc.sql("UPDATE admin_accounts SET failed_attempts = :f, locked_until = :lu, updated_at = :now WHERE id = :id")
                .param("f", failures)
                .param("lu", lockUntil)
                .param("now", ts().value())
                .param("id", a.id())
                .update();
        audit.record(null, lockUntil != null ? "ADMIN_LOCKED" : "ADMIN_LOGIN_FAILED", "ADMIN", a.id(),
                passwordOk ? "BAD_SECOND_FACTOR" : "BAD_PASSWORD", null, null);
    }

    private boolean consumeRecoveryCode(UUID adminId, String input, Instant now) {
        String normalized = input == null ? "" : input.replace("-", "").replace(" ", "").toUpperCase();
        if (!RECOVERY_INPUT.matcher(normalized).matches()) {
            return false;
        }
        return jdbc.sql("""
                                UPDATE admin_recovery_codes SET used_at = :now
                                WHERE admin_id = :a AND code_hash = :h AND used_at IS NULL
                                """)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .param("a", adminId)
                        .param("h", sha256(normalized))
                        .update()
                == 1;
    }

    private int recoveryCodesLeft(UUID adminId) {
        return jdbc.sql("SELECT count(*) FROM admin_recovery_codes WHERE admin_id = :a AND used_at IS NULL")
                .param("a", adminId)
                .query(Integer.class)
                .single();
    }

    private void storeRecoveryCodes(UUID adminId, List<String> codes) {
        for (String code : codes) {
            jdbc.sql("INSERT INTO admin_recovery_codes (admin_id, code_hash) VALUES (:a, :h)")
                    .param("a", adminId)
                    .param("h", sha256(code.replace("-", "")))
                    .update();
        }
    }

    /** Отзывает сессии администратора; exceptTokenHash (если задан) остаётся действующим. */
    private void revokeAllSessions(UUID adminId, String exceptTokenHash) {
        if (exceptTokenHash == null) {
            jdbc.sql("UPDATE admin_sessions SET revoked_at = :now WHERE admin_id = :a AND revoked_at IS NULL")
                    .param("now", ts().value())
                    .param("a", adminId)
                    .update();
        } else {
            jdbc.sql("""
                            UPDATE admin_sessions SET revoked_at = :now
                            WHERE admin_id = :a AND revoked_at IS NULL AND token_hash <> :except
                            """)
                    .param("now", ts().value())
                    .param("a", adminId)
                    .param("except", exceptTokenHash)
                    .update();
        }
    }

    private String loginOf(UUID id) {
        return jdbc.sql("SELECT login FROM admin_accounts WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ADMIN_NOT_FOUND", "error.admin.notFound"));
    }

    private static void requireNotSelf(UUID actor, UUID target) {
        if (actor != null && actor.equals(target)) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_TARGET_SELF", "error.admin.self");
        }
    }

    private static void requireReason(String reasonCode) {
        if (reasonCode == null || !REASON.matcher(reasonCode).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "error.reason.required");
        }
    }

    private Creds newCreds(UUID adminId) {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        StringBuilder pw = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            pw.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            byte[] raw = new byte[10];
            random.nextBytes(raw);
            String b32 = Base32.encode(raw); // 16 символов
            codes.add(b32.substring(0, 4) + "-" + b32.substring(4, 8) + "-" + b32.substring(8, 12) + "-" + b32.substring(12, 16));
        }
        return new Creds(pw.toString(), Base32.encode(secret), box.seal(secret, adminId.toString()), codes);
    }

    private static Enrollment enrollment(UUID id, String login, Creds c) {
        String label = URLEncoder.encode("JolOmo:" + login, StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + label + "?secret=" + c.secretBase32() + "&issuer=JolOmo&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(id, login, c.password(), c.secretBase32(), uri, c.recoveryCodes());
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private Timestamp ts() {
        return new Timestamp(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private record Timestamp(OffsetDateTime value) {}

    private record Creds(String password, String secretBase32, byte[] sealedSecret, List<String> recoveryCodes) {}

    private record Row(
            UUID id, String login, String displayName, AdminRole role, String status, String passwordHash,
            byte[] secretEnc, long lastStep, int failedAttempts, OffsetDateTime lockedUntil) {}
}
