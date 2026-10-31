package kg.jolomo.push;

import java.net.URI;

/** Одна отправка «разбуди устройство». Без полезной нагрузки: в ней нечему содержать контакты. Не бросает исключений. */
@FunctionalInterface
public interface PushSender {

    /** ACCEPTED: push-служба приняла (это не «доставлено»); GONE: подписки больше нет; FAILED: временный или иной сбой. */
    enum Outcome { ACCEPTED, GONE, FAILED }

    Outcome send(URI endpoint);
}
