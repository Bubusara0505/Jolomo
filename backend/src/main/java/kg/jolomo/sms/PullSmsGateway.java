package kg.jolomo.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Шлюз «устройство опрашивает сервер»: сервер ничего не отправляет сам, сообщения забирает телефон (SmsGatewayService).
 * Поэтому push-отправщик SmsDispatcher должен быть выключен: иначе он посчитал бы «отправку» сбоем и за секунды исчерпал бы попытки.
 * Это проверяется при старте: приложение не запустится с jolomo.sms.gateway=android и включённым dispatcher.
 */
@Component
@ConditionalOnProperty(name = "jolomo.sms.gateway", havingValue = "android")
public class PullSmsGateway implements SmsGateway {

    public PullSmsGateway(@Value("${jolomo.sms.dispatcher.enabled:true}") boolean dispatcherEnabled) {
        if (dispatcherEnabled) {
            throw new IllegalStateException(
                    "jolomo.sms.gateway=android требует выключенного dispatcher: JOLOMO_SMS_DISPATCHER_ENABLED=false");
        }
    }

    @Override
    public boolean submit(String commandId, String phone, String body) {
        throw new UnsupportedOperationException("Сообщения забирает устройство шлюза, сервер сам не отправляет");
    }
}
