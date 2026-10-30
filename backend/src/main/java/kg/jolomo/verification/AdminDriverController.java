package kg.jolomo.verification;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kg.jolomo.admin.AdminAuth;
import kg.jolomo.admin.AdminPermission;
import kg.jolomo.admin.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Приостановка водителя поддержкой (SP11-01). Право DRIVER_SUSPEND, код причины и строка аудита обязательны.
 * Приостановка лишь лишает допуска (EligibilityService); вместимость и доставку она не меняет. Снятие возвращает
 * прежний статус, а не выдаёт допуск заново.
 */
@RestController
@RequestMapping("/api/v1/admin/drivers")
class AdminDriverController {

    record ReasonRequest(@NotBlank @Size(max = 64) String reasonCode) {}

    private final AdminAuth auth;
    private final DriverProfileService profiles;

    AdminDriverController(AdminAuth auth, DriverProfileService profiles) {
        this.auth = auth;
        this.profiles = profiles;
    }

    @PostMapping("/{userId}/suspend")
    ResponseEntity<Void> suspend(HttpServletRequest request, @PathVariable UUID userId, @Valid @RequestBody ReasonRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.DRIVER_SUSPEND);
        profiles.suspend(userId, admin.id(), body.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{userId}/lift-suspension")
    ResponseEntity<Void> lift(HttpServletRequest request, @PathVariable UUID userId, @Valid @RequestBody ReasonRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.DRIVER_SUSPEND);
        profiles.liftSuspension(userId, admin.id(), body.reasonCode());
        return ResponseEntity.noContent().build();
    }
}
