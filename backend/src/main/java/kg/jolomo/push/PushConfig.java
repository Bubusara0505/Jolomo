package kg.jolomo.push;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PushConfig {

    @Bean
    PushSettings pushSettings(
            @Value("${jolomo.push.enabled:false}") boolean enabled,
            @Value("${jolomo.push.vapid.public-key:}") String publicKey,
            @Value("${jolomo.push.vapid.private-key:}") String privateKey,
            @Value("${jolomo.push.vapid.subject:}") String subject,
            @Value("${jolomo.push.allowed-hosts:}") String allowedHosts,
            @Value("${jolomo.push.subscription-days:90}") int subscriptionDays,
            @Value("${jolomo.push.max-failures:5}") int maxFailures) {
        List<String> hosts = Arrays.stream(allowedHosts.split(",")).map(String::trim).filter(h -> !h.isEmpty()).toList();
        return new PushSettings(enabled, publicKey, privateKey, subject, hosts, subscriptionDays, maxFailures);
    }

    @Bean
    EndpointPolicy endpointPolicy(PushSettings settings) {
        return new EndpointPolicy(settings.allowedHosts());
    }

    /** Включено без ключей: приложение не стартует. Выключено: отправитель всегда отвечает FAILED и ничего не вызывает. */
    @Bean
    PushSender pushSender(PushSettings settings, Clock clock) {
        if (!settings.enabled()) {
            return endpoint -> PushSender.Outcome.FAILED;
        }
        VapidSigner signer = new VapidSigner(settings.publicKey(), settings.privateKey(), settings.subject(), clock);
        return new HttpPushSender(HttpPushSender.newClient(), signer);
    }
}
