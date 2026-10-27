package kg.jolomo.admin;

import java.time.Duration;

/** sessionTtl фиксированный (без продления): через 30 минут нужен новый вход с TOTP. */
public record AdminSettings(
        Duration sessionTtl, int maxFailures, Duration lockDuration, int pbkdf2Iterations, boolean cookieSecure) {}
