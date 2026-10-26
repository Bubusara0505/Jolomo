package kg.jolomo.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SmsConfig {

    /** Ключ обязателен: без него приложение не стартует (как OTP_PEPPER). */
    @Bean
    SmsBodyCipher smsBodyCipher(@Value("${jolomo.sms.body-key:}") String key) {
        return new SmsBodyCipher(key);
    }
}
