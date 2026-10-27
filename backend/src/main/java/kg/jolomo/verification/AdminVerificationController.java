package kg.jolomo.verification;

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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Разбор заявок администратором. Каждый маршрут требует действующую админскую сессию и право VERIFICATION_REVIEW.
 * Байты документа отдаёт только маршрут documents/{token}: токен живёт 5 минут и привязан к администратору.
 */
@RestController
@RequestMapping("/api/v1/admin/verification")
class AdminVerificationController {

    enum Decision { APPROVE, REJECT }

    record ReasonRequest(@NotBlank @Size(max = 64) String reasonCode) {}

    record DecisionRequest(@NotNull Decision decision, @NotBlank @Size(max = 64) String reasonCode) {}

    record AccessRequest(@NotBlank @Size(max = 64) String purposeCode) {}

    private final AdminAuth auth;
    private final VerificationReviewService review;
    private final IdentityAccessService access;

    AdminVerificationController(AdminAuth auth, VerificationReviewService review, IdentityAccessService access) {
        this.auth = auth;
        this.review = review;
        this.access = access;
    }

    @GetMapping("/cases")
    List<VerificationReviewService.QueueItem> queue(
            HttpServletRequest request,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        auth.require(request, AdminPermission.VERIFICATION_REVIEW);
        return review.queue(status, limit);
    }

    @GetMapping("/cases/{id}")
    VerificationReviewService.CaseDetail detail(HttpServletRequest request, @PathVariable UUID id) {
        auth.require(request, AdminPermission.VERIFICATION_REVIEW);
        return review.detail(id);
    }

    @PostMapping("/cases/{id}/start")
    ResponseEntity<Void> start(HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.VERIFICATION_REVIEW);
        review.startReview(admin.id(), id, body.reasonCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/cases/{id}/decision")
    ResponseEntity<Void> decide(HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.VERIFICATION_REVIEW);
        review.decide(admin.id(), id, body.decision() == Decision.APPROVE, body.reasonCode());
        return ResponseEntity.noContent().build();
    }

    /** Выдать короткую ссылку на файл (5 минут). Нужна цель просмотра. */
    @PostMapping("/objects/{objectId}/access")
    ResponseEntity<IdentityAccessService.Grant> grant(
            HttpServletRequest request, @PathVariable UUID objectId, @Valid @RequestBody AccessRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.VERIFICATION_REVIEW);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(access.grant(admin.id(), objectId, body.purposeCode()));
    }

    /** Единственный маршрут, отдающий байты документа. Право проверяется заново при каждом скачивании. */
    @GetMapping("/documents/{token}")
    ResponseEntity<byte[]> download(HttpServletRequest request, @PathVariable String token) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.VERIFICATION_REVIEW);
        IdentityAccessService.Download d = access.open(admin.id(), token);
        String ext = switch (d.contentType()) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".pdf";
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("document-" + d.objectId() + ext).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(d.bytes());
    }
}
