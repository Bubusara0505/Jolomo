package kg.jolomo.push;

import java.util.List;

/**
 * Настройки Web Push. Выключено по умолчанию: opt-in работает только когда сервер настроен ключами VAPID.
 * Ключи только из окружения (PUSH_VAPID_PUBLIC_KEY, PUSH_VAPID_PRIVATE_KEY), не равны другим секретам.
 *
 * @param allowedHosts    домены push-служб браузеров; адрес подписки вне списка не принимается (защита от SSRF)
 * @param subscriptionDays срок жизни подписки без обновления; приложение продлевает её повторным opt-in
 * @param maxFailures     столько сбоев подряд, и подписка отзывается
 */
public record PushSettings(
        boolean enabled,
        String publicKey,
        String privateKey,
        String subject,
        List<String> allowedHosts,
        int subscriptionDays,
        int maxFailures) {

    public static final List<String> DEFAULT_ALLOWED_HOSTS =
            List.of("fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com");

    public PushSettings {
        allowedHosts = allowedHosts == null || allowedHosts.isEmpty() ? DEFAULT_ALLOWED_HOSTS : List.copyOf(allowedHosts);
        if (subscriptionDays < 1 || maxFailures < 1) {
            throw new IllegalStateException("Некорректные настройки Web Push: срок и число сбоев должны быть положительными");
        }
    }
}
