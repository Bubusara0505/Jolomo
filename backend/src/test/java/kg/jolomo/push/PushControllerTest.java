package kg.jolomo.push;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP11-02: маршруты Web Push. Ключ публичный, всё остальное только для вошедшего и без адресов в ответах. */
class PushControllerTest {

    private static final String BODY = "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/abc\",\"expirationTime\":null,"
            + "\"keys\":{\"p256dh\":\"BNc\",\"auth\":\"xyz\"}}";

    private final SessionAuth auth = mock(SessionAuth.class);
    private final PushSubscriptionService subscriptions = mock(PushSubscriptionService.class);
    private final UUID user = UUID.randomUUID();

    private MockMvc mvc(boolean enabled) {
        PushSettings settings = new PushSettings(enabled, " PUBKEY ", "priv", "mailto:ops@jolomo.kg", List.of(), 90, 5);
        return MockMvcBuilders.standaloneSetup(new PushController(auth, subscriptions, settings))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private void signedIn() {
        when(auth.requireUserId(any())).thenReturn(user);
    }

    @Test
    void thePublicKeyIsPublicAndTrimmed() throws Exception {
        mvc(true).perform(get("/api/v1/push/vapid-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value("PUBKEY"));
    }

    @Test
    void withoutServerKeysThePublicKeyIs503SoTheAppHidesOptIn() throws Exception {
        mvc(false).perform(get("/api/v1/push/vapid-key"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PUSH_DISABLED"));
    }

    @Test
    void everythingPersonalNeedsASession() throws Exception {
        when(auth.requireUserId(any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));
        MockMvc mvc = mvc(true);

        mvc.perform(get("/api/v1/me/push-subscriptions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/push-subscriptions").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/me/push-subscriptions/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/me/push-subscriptions/revoke").contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"https://fcm.googleapis.com/x\"}"))
                .andExpect(status().isUnauthorized());

        verify(subscriptions, never()).subscribe(any(), any(), any());
    }

    @Test
    void optInAcceptsTheBrowserJsonAndNeverEchoesTheAddress() throws Exception {
        signedIn();
        UUID id = UUID.randomUUID();
        when(subscriptions.subscribe(eq(user), eq("https://fcm.googleapis.com/fcm/send/abc"), any()))
                .thenReturn(new PushSubscriptionService.Subscription(id, Instant.parse("2026-10-31T10:00:00Z"), Instant.parse("2027-01-29T10:00:00Z")));

        mvc(true).perform(post("/api/v1/me/push-subscriptions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.endpoint").doesNotExist());
    }

    @Test
    void aBlankOrMissingEndpointIsABadRequest() throws Exception {
        signedIn();
        MockMvc mvc = mvc(true);

        mvc.perform(post("/api/v1/me/push-subscriptions").contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/me/push-subscriptions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void serviceErrorsKeepTheirCodes() throws Exception {
        signedIn();
        when(subscriptions.subscribe(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "PUSH_ENDPOINT_INVALID", "error.push.endpoint_invalid"));

        mvc(true).perform(post("/api/v1/me/push-subscriptions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PUSH_ENDPOINT_INVALID"));
    }

    @Test
    void listShowsOnlyIdsAndDates() throws Exception {
        signedIn();
        when(subscriptions.list(user)).thenReturn(List.of(
                new PushSubscriptionService.Subscription(UUID.randomUUID(), Instant.parse("2026-10-31T10:00:00Z"), Instant.parse("2027-01-29T10:00:00Z"))));

        mvc(true).perform(get("/api/v1/me/push-subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].endpoint").doesNotExist());
    }

    @Test
    void unsubscribingByIdAndByEndpointAnswers204() throws Exception {
        signedIn();
        UUID id = UUID.randomUUID();
        MockMvc mvc = mvc(false);   // отписаться можно и когда сервер push не настроен

        mvc.perform(delete("/api/v1/me/push-subscriptions/" + id)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/me/push-subscriptions/revoke").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/abc\"}"))
                .andExpect(status().isNoContent());

        verify(subscriptions).revoke(user, id);
        verify(subscriptions).revokeByEndpoint(user, "https://fcm.googleapis.com/fcm/send/abc");
    }

    @Test
    void someoneElsesSubscriptionIs404() throws Exception {
        signedIn();
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.NOT_FOUND, "PUSH_SUBSCRIPTION_NOT_FOUND", "error.push.subscription_not_found"))
                .when(subscriptions).revoke(user, id);

        mvc(true).perform(delete("/api/v1/me/push-subscriptions/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUSH_SUBSCRIPTION_NOT_FOUND"));
    }
}
