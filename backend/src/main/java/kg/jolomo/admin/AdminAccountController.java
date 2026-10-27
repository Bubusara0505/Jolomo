package kg.jolomo.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Управление учётными записями администраторов. Только право ADMIN_MANAGE (владелец), всегда с кодом причины. */
@RestController
@RequestMapping("/api/v1/admin/accounts")
class AdminAccountController {

    record CreateRequest(
            @NotBlank @Size(max = 64) String login,
            @NotBlank @Size(max = 100) String displayName,
            @NotNull AdminRole role,
            @NotBlank @Size(max = 64) String reasonCode) {}

    record ReasonRequest(@NotBlank @Size(max = 64) String reasonCode) {}

    private final AdminService admins;
    private final AdminAuth auth;

    AdminAccountController(AdminService admins, AdminAuth auth) {
        this.admins = admins;
        this.auth = auth;
    }

    /** Секреты в ответе показываются один раз и не кэшируются. */
    @PostMapping
    ResponseEntity<AdminService.Enrollment> create(HttpServletRequest request, @Valid @RequestBody CreateRequest body) {
        AdminService.AdminPrincipal actor = auth.require(request, AdminPermission.ADMIN_MANAGE);
        AdminService.Enrollment e = admins.create(body.login(), body.displayName(), body.role(), actor.id(), body.reasonCode());
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store").body(e);
    }

    @PostMapping("/{id}/disable")
    ResponseEntity<Void> disable(HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        AdminService.AdminPrincipal actor = auth.require(request, AdminPermission.ADMIN_MANAGE);
        admins.disable(actor.id(), id, body.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset")
    ResponseEntity<AdminService.Enrollment> reset(HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        AdminService.AdminPrincipal actor = auth.require(request, AdminPermission.ADMIN_MANAGE);
        AdminService.Enrollment e = admins.reset(actor.id(), id, body.reasonCode());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(e);
    }
}
