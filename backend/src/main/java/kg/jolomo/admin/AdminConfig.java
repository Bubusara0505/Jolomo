package kg.jolomo.admin;

import java.time.Duration;
import kg.jolomo.shared.AesGcmBox;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AdminConfig {

    @Bean
    AdminSettings adminSettings(
            @Value("${jolomo.admin.session-minutes:30}") long sessionMinutes,
            @Value("${jolomo.admin.max-failures:5}") int maxFailures,
            @Value("${jolomo.admin.lock-minutes:15}") long lockMinutes,
            @Value("${jolomo.admin.pbkdf2-iterations:600000}") int iterations,
            @Value("${jolomo.auth.session.cookie-secure:true}") boolean cookieSecure) {
        return new AdminSettings(Duration.ofMinutes(sessionMinutes), maxFailures, Duration.ofMinutes(lockMinutes), iterations, cookieSecure);
    }

    @Bean
    PasswordHasher passwordHasher(AdminSettings settings) {
        return new PasswordHasher(settings.pbkdf2Iterations());
    }

    /** Ключ шифрования секретов TOTP. Без него приложение не стартует. */
    @Bean
    @Qualifier("adminTotpBox")
    AesGcmBox adminTotpBox(@Value("${jolomo.admin.totp-key:}") String key) {
        return new AesGcmBox(key, "ADMIN_TOTP_KEY");
    }
}
