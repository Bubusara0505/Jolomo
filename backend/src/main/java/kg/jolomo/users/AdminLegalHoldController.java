package kg.jolomo.users;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import kg.jolomo.admin.AdminAuth;
import kg.jolomo.admin.AdminPermission;
import kg.jolomo.admin.AdminService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Legal hold: постановка, снятие и просмотр. Право LEGAL_HOLD есть только у владельца. */
@RestController
@RequestMapping("/api/v1/admin/legal-holds")
class AdminLegalHoldController {

    record PlaceRequest(@NotNull UUID userId, @NotBlank @Size(max = 64) String reasonCode) {}

    record ReleaseRequest(@NotBlank @Size(max = 64) String reasonCode) {}

    private final AdminAuth auth;
    private final LegalHoldService holds;

    AdminLegalHoldController(AdminAuth auth, LegalHoldService holds) {
        this.auth = auth;
        this.holds = holds;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    LegalHoldService.Hold place(HttpServletRequest request, @Valid @RequestBody PlaceRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.LEGAL_HOLD);
        return holds.place(admin.id(), body.userId(), body.reasonCode());
    }

    @PostMapping("/{id}/release")
    LegalHoldService.Hold release(HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody ReleaseRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.LEGAL_HOLD);
        return holds.release(admin.id(), id, body.reasonCode());
    }

    @GetMapping
    List<LegalHoldService.Hold> list(HttpServletRequest request, @RequestParam UUID userId) {
        auth.require(request, AdminPermission.LEGAL_HOLD);
        return holds.list(userId);
    }
}
