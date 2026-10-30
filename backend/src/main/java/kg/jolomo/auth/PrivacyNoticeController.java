package kg.jolomo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.users.PrivacyNoticeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Версия уведомления: действующую версию видит любой, принятую версию видит и принимает только сам пользователь (CSRF на POST). */
@RestController
@RequestMapping("/api/v1")
class PrivacyNoticeController {

    record NoticeView(String version, Instant publishedAt) {}

    record AcceptRequest(@NotBlank @Size(max = 32) String version) {}

    record AcceptanceView(String version, Instant acceptedAt) {}

    private final SessionAuth auth;
    private final PrivacyNoticeService notices;

    PrivacyNoticeController(SessionAuth auth, PrivacyNoticeService notices) {
        this.auth = auth;
        this.notices = notices;
    }

    @GetMapping("/privacy-notice/current")
    NoticeView current() {
        return notices.current()
                .map(n -> new NoticeView(n.version(), n.publishedAt()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "error.notice.not_found"));
    }

    @GetMapping("/me/privacy-notice")
    AcceptanceView mine(HttpServletRequest request) {
        UUID userId = auth.requireUserId(request);
        return notices.acceptanceOf(userId)
                .map(a -> new AcceptanceView(a.version(), a.acceptedAt()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOTICE_NOT_ACCEPTED", "error.notice.not_accepted"));
    }

    @PostMapping("/me/privacy-notice")
    AcceptanceView accept(HttpServletRequest request, @Valid @RequestBody AcceptRequest body) {
        UUID userId = auth.requireUserId(request);
        PrivacyNoticeService.Acceptance a = notices.accept(userId, body.version());
        return new AcceptanceView(a.version(), a.acceptedAt());
    }
}
