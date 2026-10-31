package kg.jolomo.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Раз в минуту переводит принятые устройством, но не подтвердившиеся сообщения в UNKNOWN. */
@Component
@ConditionalOnProperty(name = "jolomo.sms.gateway", havingValue = "android")
class SmsGatewayJob {

    private final SmsGatewayService gateway;

    SmsGatewayJob(SmsGatewayService gateway) {
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${jolomo.sms.gateway-unknown-check-ms:60000}")
    void run() {
        gateway.markSilentAsUnknown();
    }
}
