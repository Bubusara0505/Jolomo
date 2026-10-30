package kg.jolomo.parcels;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DeliveryConfig {

    @Bean
    DeliverySettings deliverySettings(
            @Value("${jolomo.delivery.pepper:}") String pepper,
            @Value("${jolomo.delivery.ttl-minutes:120}") long ttlMinutes,
            @Value("${jolomo.delivery.resend-seconds:60}") long resendSeconds,
            @Value("${jolomo.delivery.max-per-hour:5}") int maxPerHour,
            @Value("${jolomo.delivery.link-base:http://localhost:3000/r}") String linkBase) {
        return new DeliverySettings(pepper, Duration.ofMinutes(ttlMinutes), Duration.ofSeconds(resendSeconds), maxPerHour, linkBase);
    }
}
