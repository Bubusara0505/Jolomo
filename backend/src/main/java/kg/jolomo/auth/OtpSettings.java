package kg.jolomo.auth;

import java.time.Duration;

public record OtpSettings(
        String pepper, Duration ttl, int maxAttempts, Duration resendInterval, int maxPerHour) {

    public OtpSettings {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("OTP_PEPPER не задан");
        }
    }
}
