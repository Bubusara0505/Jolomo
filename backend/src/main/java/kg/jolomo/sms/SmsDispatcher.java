package kg.jolomo.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jolomo.sms.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
class SmsDispatcher {

    private final SmsOutboxService outbox;

    SmsDispatcher(SmsOutboxService outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${jolomo.sms.dispatch-delay-ms:2000}")
    void run() {
        outbox.dispatchPending(20);
    }
}
