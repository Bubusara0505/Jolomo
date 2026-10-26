package kg.jolomo.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    record OtpVerified(boolean verified, UUID userId) {}

    private final OtpService otp;
    private final SessionService sessions;
    private final SessionCookies cookies;

    OtpController(OtpService otp, SessionService sessions, SessionCookies cookies) {
        this.otp = otp;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    OtpIssued request(@Valid @RequestBody OtpRequest body) {
        OtpService.Issued issued = otp.request(body.phone(), body.purpose());
        return new OtpIssued(issued.challengeId(), issued.expiresAt(), issued.resendAfterSeconds());
    }

    /** Верный код создаёт сессию: access и refresh приходят только в HttpOnly cookie, не в теле. */
    @PostMapping("/verify")
    ResponseEntity<OtpVerified> verify(@Valid @RequestBody OtpVerify body) {
        OtpService.VerifyResult result = otp.verify(body.challengeId(), body.phone(), body.purpose(), body.code());
        switch (result) {
            case EXPIRED -> throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "error.otp.expired");
            case EXHAUSTED -> throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "OTP_ATTEMPTS_EXHAUSTED", "error.otp.exhausted");
            case INVALID -> throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "error.otp.invalid");
            case OK -> {
                // продолжаем: выдаём сессию
            }
        }
        SessionService.Tokens tokens = sessions.startSession(body.phone());
        return ResponseEntity.ok()
                .headers(h -> h.addAll(HttpHeaders.SET_COOKIE, cookies.issue(tokens)))
                .body(new OtpVerified(true, tokens.userId()));
    }
}
