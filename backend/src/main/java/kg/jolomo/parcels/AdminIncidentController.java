package kg.jolomo.parcels;

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
import kg.jolomo.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Инциденты поддержки. Только администратор с правом INCIDENT_RECORD; ни одно действие не завершает доставку. */
@RestController
@RequestMapping("/api/v1/admin/incidents")
class AdminIncidentController {

    record RecordRequest(@NotNull UUID parcelId, @NotBlank @Size(max = 64) String kind, @NotBlank @Size(max = 64) String reasonCode) {}

    private final AdminAuth auth;
    private final IncidentService incidents;

    AdminIncidentController(AdminAuth auth, IncidentService incidents) {
        this.auth = auth;
        this.incidents = incidents;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IncidentService.Incident record(HttpServletRequest request, @Valid @RequestBody RecordRequest body) {
        AdminService.AdminPrincipal admin = auth.require(request, AdminPermission.INCIDENT_RECORD);
        return incidents.record(admin.id(), body.parcelId(), body.kind(), body.reasonCode());
    }

    @GetMapping
    List<IncidentService.Incident> list(HttpServletRequest request, @RequestParam UUID parcelId,
                                        @RequestParam(defaultValue = "50") int limit) {
        auth.require(request, AdminPermission.INCIDENT_RECORD);
        return incidents.list(parcelId, limit);
    }

    @GetMapping("/{id}")
    IncidentService.Incident get(HttpServletRequest request, @PathVariable UUID id) {
        auth.require(request, AdminPermission.INCIDENT_RECORD);
        return incidents.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "error.incident.not_found"));
    }
}
