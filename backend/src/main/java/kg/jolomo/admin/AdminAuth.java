package kg.jolomo.admin;

import jakarta.servlet.http.HttpServletRequest;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Единая проверка «кто админ и что ему можно» для всех маршрутов /api/v1/admin. Отказ в праве попадает в аудит. */
@Component
public class AdminAuth {

    private final AdminService admins;

    AdminAuth(AdminService admins) {
        this.admins = admins;
    }

    public AdminService.AdminPrincipal require(HttpServletRequest request, AdminPermission permission) {
        AdminService.AdminPrincipal p = authenticated(request);
        if (!p.role().allows(permission)) {
            admins.recordDenied(p, permission);
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_FORBIDDEN", "error.admin.forbidden");
        }
        return p;
    }

    /** Любой действующий администратор, без проверки конкретного права. */
    public AdminService.AdminPrincipal authenticated(HttpServletRequest request) {
        return AdminCookies.read(request, AdminCookies.SESSION)
                .flatMap(admins::authenticate)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHENTICATED", "error.admin.unauthenticated"));
    }

    public String token(HttpServletRequest request) {
        return AdminCookies.read(request, AdminCookies.SESSION).orElse("");
    }
}
