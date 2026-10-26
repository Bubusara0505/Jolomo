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
            @Value("${jolomo.auth.otp.max-per-hour:5}") int maxPerHour,
            @Value("${jolomo.auth.otp.max-global-per-minute:30}") int maxGlobalPerMinute) {
        return new OtpSettings(
                pepper,
                Duration.ofSeconds(ttlSeconds),
                maxAttempts,
                Duration.ofSeconds(resendSeconds),
                maxPerHour,
                maxGlobalPerMinute);
    }

    @Bean
    SessionSettings sessionSettings(
            @Value("${jolomo.auth.session.secret:}") String secret,
            @Value("${jolomo.auth.session.access-ttl-seconds:900}") long accessSeconds,
            @Value("${jolomo.auth.session.refresh-ttl-days:30}") long refreshDays,
            @Value("${jolomo.auth.session.cookie-secure:true}") boolean cookieSecure) {
        return new SessionSettings(secret, Duration.ofSeconds(accessSeconds), Duration.ofDays(refreshDays), cookieSecure);
    }
}
