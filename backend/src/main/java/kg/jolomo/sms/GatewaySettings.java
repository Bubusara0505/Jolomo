package kg.jolomo.sms;

import java.time.Duration;

/**
 * lease: сколько сообщение закреплено за устройством без подтверждения, потом возвращается в очередь.
 * unknownAfter: через сколько после принятия устройством без единого отчёта статус становится UNKNOWN.
 */
public record GatewaySettings(Duration lease, Duration unknownAfter) {

    public GatewaySettings {
        if (lease == null || lease.isNegative() || lease.isZero() || unknownAfter == null || unknownAfter.isNegative()
                || unknownAfter.isZero()) {
            throw new IllegalStateException("Сроки шлюза должны быть положительными");
        }
    }
}
