package kg.jolomo.shared;

/** Единый конверт ошибки: код, ключ сообщения для перевода и correlationId. Трассы стека нет. */
public record ApiError(String code, String message, String correlationId) {}
