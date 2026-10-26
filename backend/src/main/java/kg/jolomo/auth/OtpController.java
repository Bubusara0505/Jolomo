package kg.jolomo.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/otp")
class OtpController {

    private static final String PHONE = "^\\+996\\d{9}$";

    record OtpRequest(@NotBlank @Pattern(regexp = PHONE) String phone, @NotNull Purpose purpose) {}

    record OtpIssued(UUID challengeId, Instant expiresAt, long resendAfterSeconds) {}

    record OtpVerify(
            @NotNull UUID challengeId,
            @NotBlank @Pattern(regexp = PHONE) String phone,
            @NotNull Purpose purpose,
            @NotBlank @Size(min = 6, max = 6) @Pattern(regexp = "^\\d{6}$") String code) {}

    record OtpVerified(boolean verified) {}

    private final OtpService otp;

    OtpController(OtpService otp) {
        this.otp = otp;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OtpIssued request(@Valid @RequestBody OtpRequest body) {
        OtpService.Issued issued = otp.request(body.phone(), body.purpose());
        return new OtpIssued(issued.challengeId(), issued.expiresAt(), issued.resendAfterSeconds());
    }

    /** Пока без выдачи сессии: она появится в SP02-04 (ждёт бюджет попыток SP02-03). */
    @PostMapping("/verify")
    OtpVerified verify(@Valid @RequestBody OtpVerify body) {
        return switch (otp.verify(body.challengeId(), body.phone(), body.purpose(), body.code())) {
            case OK -> new OtpVerified(true);
            case EXPIRED -> throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "error.otp.expired");
            case EXHAUSTED -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "OTP_ATTEMPTS_EXHAUSTED", "error.otp.exhausted");
            case INVALID -> throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "error.otp.invalid");
        };
    }
}
