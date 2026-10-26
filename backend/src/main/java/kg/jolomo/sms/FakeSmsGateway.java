package kg.jolomo.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Имитатор шлюза: SMS не отправляет. Текст (с кодом) пишет в лог только при явном dev-флаге. */
@Component
@ConditionalOnProperty(name = "jolomo.sms.gateway", havingValue = "fake", matchIfMissing = true)
public class FakeSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(FakeSmsGateway.class);

    private final boolean logBody;

    public FakeSmsGateway(@Value("${jolomo.sms.fake.log-body:false}") boolean logBody, Environment env) {
        // Код в журнале нарушает правило ТЗ, поэтому разрешаем только в профиле dev.
        if (logBody && !env.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException("SMS_FAKE_LOG_BODY=true допустим только с профилем dev (SPRING_PROFILES_ACTIVE=dev)");
        }
        this.logBody = logBody;
    }

    @Override
    public boolean submit(String commandId, String phone, String body) {
        String masked = phone.length() > 4 ? "***" + phone.substring(phone.length() - 4) : "***";
        if (logBody) {
            log.warn("FAKE SMS (dev) command={} to={} body={}", commandId, masked, body);
        } else {
            log.info("FAKE SMS accepted command={} to={}", commandId, masked);
        }
        return true;
    }
}
