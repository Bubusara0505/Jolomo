package kg.jolomo.verification;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Профиль водителя: только свой. Маршрутов проверяющего здесь нет, они появятся за TOTP (SP04-01). */
@RestController
@RequestMapping("/api/v1/drivers/me")
class DriverController {

    record ProfileView(DriverProfileStatus status) {}

    record EligibilityView(boolean canPublishTrip, EligibilityService.Reason reason) {}

    private final SessionAuth auth;
    private final DriverProfileService profiles;
    private final EligibilityService eligibility;

    DriverController(SessionAuth auth, DriverProfileService profiles, EligibilityService eligibility) {
        this.auth = auth;
        this.profiles = profiles;
        this.eligibility = eligibility;
    }

    /** Завести профиль водителя (UNVERIFIED). Повтор безопасен. */
    @PostMapping
    ProfileView create(HttpServletRequest request) {
        UUID userId = auth.requireUserId(request);
        return new ProfileView(profiles.create(userId).status());
    }

    @GetMapping
    ProfileView get(HttpServletRequest request) {
        UUID userId = auth.requireUserId(request);
        return profiles.find(userId)
                .map(p -> new ProfileView(p.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DRIVER_PROFILE_NOT_FOUND", "error.driver.notFound"));
    }

    /** Подсказка для экрана. Решение при публикации сервер примет заново. */
    @GetMapping("/eligibility")
    EligibilityView eligibility(HttpServletRequest request) {
        EligibilityService.Decision d = eligibility.canPublishTrip(auth.requireUserId(request));
        return new EligibilityView(d.allowed(), d.reason());
    }
}
