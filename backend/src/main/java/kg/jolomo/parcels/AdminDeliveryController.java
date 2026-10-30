package kg.jolomo.parcels;

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

/** Снятие блокировки проверки поддержкой. Доставленной посылку это не делает; право INCIDENT_RECORD, код причины, аудит. */
@RestController
@RequestMapping("/api/v1/admin/parcels")
class AdminDeliveryController {

    record ReasonRequest(@NotBlank @Size(max = 64) String reasonCode) {}

    private final AdminAuth auth;
    private final DeliveryService delivery;

    AdminDeliveryController(AdminAuth auth, DeliveryService delivery) {
        this.auth = auth;
        this.delivery = delivery;
    }

    @PostMapping("/{id}/unlock-verification")
    ResponseEntity<Void> unlock(HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.INCIDENT_RECORD);
        delivery.unlockVerification(admin.id(), id, body.reasonCode());
        return ResponseEntity.noContent().build();
    }
}
