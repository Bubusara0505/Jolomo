package kg.jolomo.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jolomo.auth.purge.enabled", havingValue = "true", matchIfMissing = true)
class OtpPurgeJob {

    private final OtpService otp;

    OtpPurgeJob(OtpService otp) {
        this.otp = otp;
    }

    @Scheduled(fixedDelayString = "${jolomo.auth.purge.delay-ms:600000}")
    void run() {
        otp.purgeStaleMaterial();
    }
}
