package kg.jolomo.shared;

/** Единый конверт ошибки: код, ключ сообщения для перевода и correlationId. Трассы стека нет. */
public record ApiError(String code, String message, String correlationId) {

    /** Конверт с correlation id текущего запроса. */
    public static ApiError of(String code, String messageKey) {
        return new ApiError(code, messageKey, org.slf4j.MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
