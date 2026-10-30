package kg.jolomo.parcels;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jolomo.delivery.purge.enabled", havingValue = "true", matchIfMissing = true)
class DeliveryPurgeJob {

    private final DeliveryService delivery;

    DeliveryPurgeJob(DeliveryService delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "${jolomo.delivery.purge.delay-ms:600000}")
    void run() {
        delivery.purgeStaleMaterial();
    }
}
