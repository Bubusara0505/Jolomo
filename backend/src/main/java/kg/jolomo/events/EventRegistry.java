package kg.jolomo.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Реестр событий (SP12-01): какие типы событий существуют и какие поля payload вообще разрешено выгружать. Выгрузка и
 * метрики читают только зарегистрированные события; незарегистрированное событие в выгрузку не попадает (запрет по
 * умолчанию). Новый тип события сначала вносится сюда и в docs/pilot-metrics-and-export.md, потом пишется в код.
 */
public final class EventRegistry {

    /** Описание типа события: к какой сущности относится и что означает. */
    public record Def(String aggregateType, String description) {}

    /** Поля payload, значения которых выгружаются как есть (короткие коды и числа, не персональные данные). */
    public static final List<String> SAFE_FIELDS = List.of(
            "from", "to", "reason", "seats", "size", "category", "obligationVersion", "kind", "parcelStatus", "termsVersion");

    /** Поля payload с идентификаторами сущностей: выгружаются только в виде псевдонима. */
    public static final List<String> ID_FIELDS = List.of("tripId", "passengerId", "senderId", "incidentId");

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        add("TRIP", "TRIP_CREATED", "черновик поездки создан");
        add("TRIP", "TRIP_UPDATED", "поездка изменена");
        add("TRIP", "TRIP_PUBLISHED", "поездка опубликована");
        add("TRIP", "TRIP_CANCELLED", "поездка отменена водителем");

        add("BOOKING", "BOOKING_REQUESTED", "пассажир запросил места");
        add("BOOKING", "BOOKING_ACCEPTED", "водитель принял запрос");
        add("BOOKING", "BOOKING_REJECTED", "водитель отклонил запрос");
        add("BOOKING", "BOOKING_CANCELLED", "бронь отменена (поле from говорит, из какого состояния)");
        add("BOOKING", "BOOKING_COMPLETED", "водитель отметил: пассажир поехал");
        add("BOOKING", "BOOKING_NO_SHOW", "водитель отметил: пассажир не приехал");

        add("PARCEL", "PARCEL_REQUESTED", "отправитель создал заявку на посылку");
        add("PARCEL", "PARCEL_ACCEPTED", "водитель принял посылку и занял слот");
        add("PARCEL", "PARCEL_REJECTED", "водитель отклонил заявку");
        add("PARCEL", "PARCEL_CANCELLED", "заявка отменена");
        add("PARCEL", "PARCEL_PICKUP_CONFIRMED", "водитель забрал посылку");
        add("PARCEL", "PARCEL_IN_TRANSIT", "посылка в пути");
        add("PARCEL", "PARCEL_DELIVERY_PENDING", "водитель на месте и ждёт получателя");
        add("PARCEL", "PARCEL_DELIVERY_CODE_SENT", "получателю отправлен код доставки (сам код не пишется)");
        add("PARCEL", "PARCEL_DELIVERED", "получатель подтвердил доставку кодом");
        add("PARCEL", "PARCEL_VERIFICATION_LOCKED", "третья ошибка кода: проверка заблокирована");
        add("PARCEL", "PARCEL_VERIFICATION_UNLOCKED", "поддержка сняла блокировку проверки");
        add("PARCEL", "PARCEL_INCIDENT_RECORDED", "поддержка записала инцидент");
    }

    private EventRegistry() {}

    private static void add(String aggregateType, String eventType, String description) {
        DEFS.put(eventType, new Def(aggregateType, description));
    }

    public static boolean known(String eventType) {
        return DEFS.containsKey(eventType);
    }

    public static Def def(String eventType) {
        return DEFS.get(eventType);
    }

    public static Set<String> types() {
        return Set.copyOf(DEFS.keySet());
    }
}
