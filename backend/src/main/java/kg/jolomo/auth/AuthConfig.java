package kg.jolomo.auth;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AuthConfig {

    @Bean
    OtpSettings otpSettings(
            @Value("${jolomo.auth.otp.pepper:}") String pepper,
            @Value("${jolomo.auth.otp.ttl-seconds:300}") long ttlSeconds,
            @Value("${jolomo.auth.otp.max-attempts:5}") int maxAttempts,
            @Value("${jolomo.auth.otp.resend-interval-seconds:60}") long resendSeconds,
            @Value("${jolomo.auth.otp.max-per-hour:5}") int maxPerHour) {
        return new OtpSettings(
                pepper, Duration.ofSeconds(ttlSeconds), maxAttempts, Duration.ofSeconds(resendSeconds), maxPerHour);
    }
}
