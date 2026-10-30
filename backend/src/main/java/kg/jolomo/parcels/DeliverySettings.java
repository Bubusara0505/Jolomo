package kg.jolomo.parcels;

import java.time.Duration;

/** Настройки кода доставки. Секрет (pepper) только из окружения и отличается от секрета телефонного OTP. */
public record DeliverySettings(String pepper, Duration ttl, Duration resendInterval, int maxPerHour, String linkBase) {

    /** По ТЗ: три ошибки, затем VERIFICATION_LOCKED. Не настраивается. */
    public static final int MAX_ATTEMPTS = 3;

    public DeliverySettings {
        if (pepper == null || pepper.length() < 16) {
            throw new IllegalStateException("DELIVERY_OTP_PEPPER не задан или короче 16 символов");
        }
    }
}
