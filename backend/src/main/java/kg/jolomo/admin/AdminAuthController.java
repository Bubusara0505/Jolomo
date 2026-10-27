package kg.jolomo.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/auth")
class AdminAuthController {

    record LoginRequest(
            @NotBlank @Size(max = 64) String login,
            @NotBlank @Size(max = 200) String password,
            @NotBlank @Size(max = 32) String code) {}

    record PasswordRequest(@NotBlank @Size(max = 200) String current, @NotBlank @Size(max = 200) String newPassword) {}

    record AdminView(UUID id, String login, String displayName, AdminRole role) {}

    record LoginResponse(AdminView admin, int recoveryCodesLeft) {}

    private final AdminService admins;
    private final AdminAuth auth;
    private final AdminCookies cookies;

    AdminAuthController(AdminService admins, AdminAuth auth, AdminCookies cookies) {
        this.admins = admins;
        this.auth = auth;
        this.cookies = cookies;
    }

    /** Пароль и TOTP (или код восстановления) в одном запросе. Любая ошибка выглядит одинаково. */
    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest body) {
        AdminService.LoginSession s = admins.login(body.login(), body.password(), body.code())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_LOGIN_FAILED", "error.admin.loginFailed"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .headers(h -> h.addAll(HttpHeaders.SET_COOKIE, cookies.issue(s.token(), s.ttl())))
                .body(new LoginResponse(view(s.admin()), s.recoveryCodesLeft()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        AdminService.AdminPrincipal p = auth.authenticated(request);
        admins.logout(p, auth.token(request));
        return ResponseEntity.noContent().headers(h -> h.addAll(HttpHeaders.SET_COOKIE, cookies.clear())).build();
    }

    @GetMapping("/me")
    AdminView me(HttpServletRequest request) {
        return view(auth.authenticated(request));
    }

    @PostMapping("/password")
    ResponseEntity<Void> password(HttpServletRequest request, @Valid @RequestBody PasswordRequest body) {
        AdminService.AdminPrincipal p = auth.authenticated(request);
        admins.changePassword(p, auth.token(request), body.current(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    private static AdminView view(AdminService.AdminPrincipal p) {
        return new AdminView(p.id(), p.login(), p.displayName(), p.role());
    }
}
