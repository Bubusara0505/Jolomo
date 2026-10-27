package kg.jolomo.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.admin.AdminAuth;
import kg.jolomo.admin.AdminPermission;
import kg.jolomo.admin.AdminRole;
import kg.jolomo.admin.AdminService;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP04-02: каждый маршрут разбора закрыт админской сессией и правом VERIFICATION_REVIEW. */
class AdminVerificationControllerTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID OBJECT = UUID.randomUUID();

    private final AdminService admins = mock(AdminService.class);
    private final VerificationReviewService review = mock(VerificationReviewService.class);
    private final IdentityAccessService access = mock(IdentityAccessService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AdminVerificationController(newAuth(), review, access))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private AdminAuth newAuth() {
        return new AdminAuth(admins);
    }

    private static final String JSON = "{\"reasonCode\":\"REVIEW_STARTED\"}";

    private void sessionAs(AdminRole role) {
        when(admins.authenticate("TOKEN"))
                .thenReturn(Optional.of(new AdminService.AdminPrincipal(ADMIN_ID, "someone", "Someone", role)));
    }

    private static Cookie admin() {
        return new Cookie("jl_admin", "TOKEN");
    }

    @Test
    void everyRouteRejectsAnonymousBeforeTouchingServices() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/api/v1/admin/verification/cases")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/verification/cases/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/verification/cases/" + id + "/start").contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/verification/cases/" + id + "/decision").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reasonCode\":\"DOCS_OK\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/verification/objects/" + OBJECT + "/access").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purposeCode\":\"IDENTITY_CHECK\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/verification/documents/some-token")).andExpect(status().isUnauthorized());

        verifyNoInteractions(review, access);
    }

    @Test
    void userCookiesDoNotOpenTheDocumentRoute() throws Exception {
        mvc.perform(get("/api/v1/admin/verification/documents/some-token").cookie(new Cookie("jl_access", "USER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHENTICATED"));
    }

    @Test
    void supportRoleHasNoAccessToVerification() throws Exception {
        sessionAs(AdminRole.SUPPORT);

        mvc.perform(get("/api/v1/admin/verification/cases").cookie(admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/verification/documents/some-token").cookie(admin()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/verification/objects/" + OBJECT + "/access").cookie(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purposeCode\":\"IDENTITY_CHECK\"}"))
                .andExpect(status().isForbidden());

        verify(admins, org.mockito.Mockito.times(3)).recordDenied(any(), eq(AdminPermission.VERIFICATION_REVIEW));
        verifyNoInteractions(review, access);
    }

    @Test
    void reviewerSeesTheQueue() throws Exception {
        sessionAs(AdminRole.REVIEWER);
        UUID caseId = UUID.randomUUID();
        when(review.queue(null, 50)).thenReturn(List.of(
                new VerificationReviewService.QueueItem(caseId, UUID.randomUUID(), "SUBMITTED", Instant.parse("2026-10-27T09:00:00Z"), 2)));

        mvc.perform(get("/api/v1/admin/verification/cases").cookie(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].caseId").value(caseId.toString()))
                .andExpect(jsonPath("$[0].files").value(2));
    }

    @Test
    void decisionPassesTheAdminIdAndReason() throws Exception {
        sessionAs(AdminRole.REVIEWER);
        UUID caseId = UUID.randomUUID();

        mvc.perform(post("/api/v1/admin/verification/cases/" + caseId + "/decision").cookie(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\",\"reasonCode\":\"DOCS_UNCLEAR\"}"))
                .andExpect(status().isNoContent());

        verify(review).decide(ADMIN_ID, caseId, false, "DOCS_UNCLEAR");
    }

    @Test
    void grantNeedsPurposeInTheBody() throws Exception {
        sessionAs(AdminRole.REVIEWER);

        mvc.perform(post("/api/v1/admin/verification/objects/" + OBJECT + "/access").cookie(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(access, never()).grant(any(), any(), any());
    }

    @Test
    void downloadIsAnAttachmentThatIsNeverCachedOrSniffed() throws Exception {
        sessionAs(AdminRole.REVIEWER);
        byte[] bytes = {1, 2, 3};
        when(access.open(ADMIN_ID, "TOK")).thenReturn(new IdentityAccessService.Download(OBJECT, "application/pdf", bytes));

        var result = mvc.perform(get("/api/v1/admin/verification/documents/TOK").cookie(admin()))
                .andExpect(status().isOk())
                .andReturn();

        var r = result.getResponse();
        assertThat(r.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(r.getContentType()).isEqualTo("application/pdf");
        assertThat(r.getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(r.getHeader("Content-Security-Policy")).contains("sandbox");
    }

    @Test
    void expiredTokenGives410InTheEnvelope() throws Exception {
        sessionAs(AdminRole.REVIEWER);
        when(access.open(ADMIN_ID, "OLD"))
                .thenThrow(new ApiException(HttpStatus.GONE, "ACCESS_EXPIRED", "error.access.expired"));

        mvc.perform(get("/api/v1/admin/verification/documents/OLD").cookie(admin()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("ACCESS_EXPIRED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }
}
