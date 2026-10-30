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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Подозрительная активность: запись и просмотр. Право ACTIVITY_FLAG (поддержка и владелец). */
@RestController
@RequestMapping("/api/v1/admin/suspicious-activity")
class AdminActivityController {

    record FlagRequest(@NotNull UUID userId, @NotBlank @Size(max = 64) String kind, @NotBlank @Size(max = 64) String reasonCode) {}

    private final AdminAuth auth;
    private final ActivityFlagService flags;

    AdminActivityController(AdminAuth auth, ActivityFlagService flags) {
        this.auth = auth;
        this.flags = flags;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ActivityFlagService.Flag flag(HttpServletRequest request, @Valid @RequestBody FlagRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.ACTIVITY_FLAG);
        return flags.record(admin.id(), body.userId(), body.kind(), body.reasonCode());
    }

    @GetMapping
    List<ActivityFlagService.Flag> list(HttpServletRequest request, @RequestParam UUID userId,
                                        @RequestParam(defaultValue = "50") int limit) {
        auth.require(request, AdminPermission.ACTIVITY_FLAG);
        return flags.list(userId, limit);
    }
}
