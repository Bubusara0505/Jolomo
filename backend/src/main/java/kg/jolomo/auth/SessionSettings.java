package kg.jolomo.auth;

import java.time.Duration;

/** Настройки сессий. Secret подписывает access-токены, задаётся только из окружения. */
public record SessionSettings(String secret, Duration accessTtl, Duration refreshTtl, boolean cookieSecure) {

    public SessionSettings {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("SESSION_SECRET не задан или короче 32 символов");
        }
    }
}
