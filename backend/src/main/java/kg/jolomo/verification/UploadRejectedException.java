package kg.jolomo.verification;

import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;

/** Загрузка отклонена. Причина короткий код, он же идёт в аудит. */
class UploadRejectedException extends RuntimeException {

    private final String reason;
    private final HttpStatus status;

    UploadRejectedException(String reason, HttpStatus status) {
        super(reason);
        this.reason = reason;
        this.status = status;
    }

    String reason() {
        return reason;
    }

    ApiException toApi() {
        return new ApiException(status, "UPLOAD_" + reason, "error.upload." + reason.toLowerCase());
    }
}
