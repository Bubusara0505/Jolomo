package kg.jolomo.parcels;

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
import java.util.Optional;
import java.util.UUID;
import kg.jolomo.admin.AdminAuth;
import kg.jolomo.admin.AdminPermission;
import kg.jolomo.admin.AdminRole;
import kg.jolomo.admin.AdminService;
import kg.jolomo.auth.SessionAuth;
import kg.jolomo.parcels.DeliveryService.VerifyOutcome;
import kg.jolomo.shared.ApiException;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP09-02 и SP09-03: маршруты кода доставки. Получатель без входа, ответы не раскрывают посылку. */
class DeliveryControllerTest {

    private static final UUID CHALLENGE = UUID.randomUUID();
    private static final UUID PARCEL = UUID.randomUUID();
    private static final String CODE = "{\"code\":\"123456\"}";

    private final SessionAuth auth = mock(SessionAuth.class);
    private final DeliveryService delivery = mock(DeliveryService.class);
    private final AdminService admins = mock(AdminService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new DeliveryController(auth, delivery), new AdminDeliveryController(new AdminAuth(admins), delivery))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    private void verifyReturns(VerifyOutcome outcome) {
        when(delivery.verify(CHALLENGE, "123456")).thenReturn(outcome);
    }

    @Test
    void driverRouteNeedsLoginAndReturnsNoCodeOrLink() throws Exception {
        when(auth.requireUserId(any())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "error.unauthenticated"));
        mvc.perform(post("/api/v1/parcels/" + PARCEL + "/delivery-challenge")).andExpect(status().isUnauthorized());

        UUID driver = UUID.randomUUID();
        org.mockito.Mockito.reset(auth);
        when(auth.requireUserId(any())).thenReturn(driver);
        when(delivery.requestChallenge(driver, PARCEL)).thenReturn(new DeliveryService.Issued(Instant.parse("2026-11-01T10:00:00Z"), 60));

        mvc.perform(post("/api/v1/parcels/" + PARCEL + "/delivery-challenge"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendAfterSeconds").value(60))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.challengeId").doesNotExist());
    }

    @Test
    void recipientVerifiesWithoutLoginAndGetsDelivered() throws Exception {
        verifyReturns(VerifyOutcome.DELIVERED);

        mvc.perform(post("/api/v1/deliveries/" + CHALLENGE + "/verify").contentType(MediaType.APPLICATION_JSON).content(CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        verifyNoInteractions(auth);
    }

    @Test
    void everyRefusalHasItsOwnStatusInTheEnvelope() throws Exception {
        verifyReturns(VerifyOutcome.INVALID_CODE);
        mvc.perform(post("/api/v1/deliveries/" + CHALLENGE + "/verify").contentType(MediaType.APPLICATION_JSON).content(CODE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_CODE_INVALID"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());

        verifyReturns(VerifyOutcome.LOCKED);
        mvc.perform(post("/api/v1/deliveries/" + CHALLENGE + "/verify").contentType(MediaType.APPLICATION_JSON).content(CODE))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("DELIVERY_LOCKED"));

        verifyReturns(VerifyOutcome.EXPIRED);
        mvc.perform(post("/api/v1/deliveries/" + CHALLENGE + "/verify").contentType(MediaType.APPLICATION_JSON).content(CODE))
                .andExpect(status().isGone());

        verifyReturns(VerifyOutcome.ALREADY_USED);
        mvc.perform(post("/api/v1/deliveries/" + CHALLENGE + "/verify").contentType(MediaType.APPLICATION_JSON).content(CODE))
                .andExpect(status().isConflict());
    }

    @Test
    void foreignLinkGivesTheSameNotFoundWhetherOrNotAParcelExists() throws Exception {
        verifyReturns(VerifyOutcome.NOT_FOUND);
        mvc.perform(post("/api/v1/deliveries/" + CHALLENGE + "/verify").contentType(MediaType.APPLICATION_JSON).content(CODE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));

        when(delivery.view(CHALLENGE)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/deliveries/" + CHALLENGE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_FOUND"));
    }

    @Test
    void blankCodeIsABadRequestAndIsNotCountedAsAnAttempt() throws Exception {
        mvc.perform(post("/api/v1/deliveries/" + CHALLENGE + "/verify").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/deliveries/" + CHALLENGE + "/verify").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(delivery, never()).verify(any(), any());
    }

    @Test
    void stateRouteShowsOnlyStateAndExpiry() throws Exception {
        when(delivery.view(CHALLENGE)).thenReturn(Optional.of(new DeliveryService.ChallengeView("ACTIVE", Instant.parse("2026-11-01T10:00:00Z"))));

        mvc.perform(get("/api/v1/deliveries/" + CHALLENGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.parcelId").doesNotExist());
    }

    @Test
    void unlockIsClosedToAnonymousAndToReviewersOpenToSupport() throws Exception {
        String url = "/api/v1/admin/parcels/" + PARCEL + "/unlock-verification";
        String body = "{\"reasonCode\":\"RECIPIENT_CONFIRMED_BY_PHONE\"}";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());

        UUID adminId = UUID.randomUUID();
        when(admins.authenticate("REV")).thenReturn(Optional.of(new AdminService.AdminPrincipal(adminId, "rev", "Rev", AdminRole.REVIEWER)));
        mvc.perform(post(url).cookie(new Cookie("jl_admin", "REV")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(admins).recordDenied(any(), eq(AdminPermission.INCIDENT_RECORD));
        verifyNoInteractions(delivery);

        when(admins.authenticate("SUP")).thenReturn(Optional.of(new AdminService.AdminPrincipal(adminId, "sup", "Sup", AdminRole.SUPPORT)));
        mvc.perform(post(url).cookie(new Cookie("jl_admin", "SUP")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        verify(delivery).unlockVerification(adminId, PARCEL, "RECIPIENT_CONFIRMED_BY_PHONE");
    }
}
