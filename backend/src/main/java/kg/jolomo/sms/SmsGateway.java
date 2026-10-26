package kg.jolomo.sms;

/** Шлюз отправки SMS. true означает только «шлюз принял» (SUBMITTED), но не доставку. */
public interface SmsGateway {

    boolean submit(String commandId, String phone, String body);
}
