package kg.jolomo.shared;

import org.springframework.http.HttpStatus;

/** Ожидаемая ошибка домена. Сообщение — ключ перевода, а не текст для человека. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String messageKey) {
        super(messageKey);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
