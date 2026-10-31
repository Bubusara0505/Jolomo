package kg.jolomo.sms;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Настройки Android-шлюза. Только при jolomo.sms.gateway=android; без устройств приложение не стартует. */
@Configuration
@ConditionalOnProperty(name = "jolomo.sms.gateway", havingValue = "android")
class GatewayConfig {

    @Bean
    GatewayDevices gatewayDevices(@Value("${SMS_GATEWAY_DEVICES:}") String spec) {
        GatewayDevices devices = new GatewayDevices(spec);
        if (devices.isEmpty()) {
            throw new IllegalStateException("jolomo.sms.gateway=android требует SMS_GATEWAY_DEVICES (имя:sha256 токена)");
        }
        return devices;
    }

    @Bean
    GatewaySettings gatewaySettings(
            @Value("${SMS_GATEWAY_LEASE_SECONDS:60}") long leaseSeconds,
            @Value("${SMS_GATEWAY_UNKNOWN_AFTER_MINUTES:15}") long unknownAfterMinutes) {
        return new GatewaySettings(Duration.ofSeconds(leaseSeconds), Duration.ofMinutes(unknownAfterMinutes));
    }
}
