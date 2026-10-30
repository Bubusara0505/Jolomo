package kg.jolomo.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import kg.jolomo.users.PrivacyNoticeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP04-03: действующую версию видит любой, принятую версию видит и принимает только сам пользователь. */
class PrivacyNoticeControllerTest {

    private final SessionAuth auth = mock(SessionAuth.class);
    private final PrivacyNoticeService notices = mock(PrivacyNoticeService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PrivacyNoticeController(auth, notices))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    @Test
    void currentVersionIsPublic() throws Exception {
        when(notices.current()).thenReturn(Optional.of(new PrivacyNoticeService.Notice("WORKING-1", Instant.parse("2026-10-31T00:00:00Z"))));

        mvc.perform(get("/api/v1/privacy-notice/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("WORKING-1"));
    }

    @Test
    void acceptingAndReadingMineNeedASession() throws Exception {
        when(auth.requireUserId(any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));

        mvc.perform(post("/api/v1/me/privacy-notice").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"WORKING-1\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/privacy-notice")).andExpect(status().isUnauthorized());

        verify(notices, never()).accept(any(), any());
    }

    @Test
    void theUserAcceptsOnlyForHimselfAndReadsItBack() throws Exception {
        UUID user = UUID.randomUUID();
        when(auth.requireUserId(any())).thenReturn(user);
        var accepted = new PrivacyNoticeService.Acceptance("WORKING-1", Instant.parse("2026-10-31T10:00:00Z"));
        when(notices.accept(user, "WORKING-1")).thenReturn(accepted);
        when(notices.acceptanceOf(user)).thenReturn(Optional.of(accepted));

        mvc.perform(post("/api/v1/me/privacy-notice").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"WORKING-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("WORKING-1"));
        mvc.perform(get("/api/v1/me/privacy-notice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("WORKING-1"));
    }

    @Test
    void notAcceptedYetIs404AndABlankVersionIsABadRequest() throws Exception {
        UUID user = UUID.randomUUID();
        when(auth.requireUserId(any())).thenReturn(user);
        when(notices.acceptanceOf(user)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/me/privacy-notice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTICE_NOT_ACCEPTED"));
        mvc.perform(post("/api/v1/me/privacy-notice").contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
