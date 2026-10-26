package kg.jolomo.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Все ошибки наружу — одним конвертом {code, message, correlationId}. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(envelope(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Стек только в журнал сервера, клиенту — ничего лишнего.
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(envelope("INTERNAL_ERROR", "error.internal"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String code;
        String key;
        if (ex instanceof MethodArgumentNotValidException) {
            code = "VALIDATION_FAILED";
            key = "error.validation";
        } else {
            HttpStatus resolved = HttpStatus.resolve(statusCode.value());
            code = resolved != null ? resolved.name() : "HTTP_" + statusCode.value();
            key = "error.http." + statusCode.value();
        }
        return ResponseEntity.status(statusCode).body(envelope(code, key));
    }

    private static ApiError envelope(String code, String messageKey) {
        return new ApiError(code, messageKey, MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
