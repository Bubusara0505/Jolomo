package kg.jolomo.verification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP03-04: маршрут загрузки только для авторизованного, без маршрута выдачи байтов. */
class VerificationControllerTest {

    private final SessionAuth auth = mock(SessionAuth.class);
    private final VerificationCaseService cases = mock(VerificationCaseService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new VerificationController(auth, cases))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private static MockMultipartFile png() {
        return new MockMultipartFile("files", "passport.png", "image/png", new byte[] {1, 2, 3});
    }

    @Test
    void anonymousUploadIsRejectedBeforeAnythingIsRead() throws Exception {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));

        mvc.perform(multipart("/api/v1/verification/cases").file(png())).andExpect(status().isUnauthorized());

        verifyNoInteractions(cases);
    }

    @Test
    void authorizedUploadReturns201() throws Exception {
        UUID user = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(auth.requireUserId(any())).thenReturn(user);
        when(cases.submit(eq(user), any())).thenReturn(new VerificationCaseService.Submitted(caseId, 1));

        mvc.perform(multipart("/api/v1/verification/cases").file(png()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseId").value(caseId.toString()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void rejectionIsReturnedInTheEnvelope() throws Exception {
        UUID user = UUID.randomUUID();
        when(auth.requireUserId(any())).thenReturn(user);
        when(cases.submit(eq(user), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "UPLOAD_TYPE_NOT_ALLOWED", "error.upload.type_not_allowed"));

        mvc.perform(multipart("/api/v1/verification/cases").file(png()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UPLOAD_TYPE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void currentCaseNeedsLogin() throws Exception {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));

        mvc.perform(get("/api/v1/verification/cases/current")).andExpect(status().isUnauthorized());
    }
}
