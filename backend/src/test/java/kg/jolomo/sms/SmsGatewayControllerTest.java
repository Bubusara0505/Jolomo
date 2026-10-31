package kg.jolomo.sms;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import kg.jolomo.shared.CorrelationIdFilter;
import kg.jolomo.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** SP10-01: HTTP-слой шлюза. Нужен токен устройства, ответы не кэшируются, события проверяются. */
class SmsGatewayControllerTest {

    private static final String TOKEN = "token-one-0123456789";
    private static final String AUTH = "Bearer " + TOKEN;

    private final SmsGatewayService gateway = mock(SmsGatewayService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new SmsGatewayController(new GatewayDevices("pixel-1:" + GatewayDevices.hashOf(TOKEN)), gateway))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();

    @Test
    void withoutAValidTokenNothingIsServedAndTheServiceIsNotAsked() throws Exception {
        for (String auth : new String[] {null, "", "Bearer ", "Bearer wrong", "Basic " + TOKEN, TOKEN}) {
            var poll = post("/api/v1/gateway/poll").contentType(MediaType.APPLICATION_JSON).content("{}");
            var events = post("/api/v1/gateway/events").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"events\":[{\"commandId\":\"a\",\"type\":\"SENT\"}]}");
            if (auth != null) {
                poll.header("Authorization", auth);
                events.header("Authorization", auth);
            }
            mvc.perform(poll).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GATEWAY_UNAUTHORIZED"));
            mvc.perform(events).andExpect(status().isUnauthorized());
        }
        verify(gateway, never()).poll(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(gateway, never()).report(any(), any());
    }

    @Test
    void pollReturnsTheLeasedMessagesWithoutCaching() throws Exception {
        when(gateway.poll(eq("pixel-1"), eq(20))).thenReturn(List.of(new SmsGatewayService.Leased("c1", "+996555000001", "Код 123456")));

        mvc.perform(post("/api/v1/gateway/poll").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.messages[0].commandId").value("c1"))
                .andExpect(jsonPath("$.messages[0].phone").value("+996555000001"));
    }

    @Test
    void pollHonoursTheRequestedLimit() throws Exception {
        when(gateway.poll(eq("pixel-1"), eq(5))).thenReturn(List.of());

        mvc.perform(post("/api/v1/gateway/poll").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty());
        verify(gateway).poll("pixel-1", 5);
    }

    @Test
    void eventsAreReportedAsTheDeviceAndResultsComeBackPerCommand() throws Exception {
        when(gateway.report(eq("pixel-1"), any())).thenReturn(List.of(
                new SmsGatewayService.EventResult("a", SmsGatewayService.Outcome.APPLIED),
                new SmsGatewayService.EventResult("b", SmsGatewayService.Outcome.IGNORED)));

        mvc.perform(post("/api/v1/gateway/events").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[{\"commandId\":\"a\",\"type\":\"DELIVERED\"},{\"commandId\":\"b\",\"type\":\"FAILED\",\"reason\":\"NO_SERVICE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.results[0].outcome").value("APPLIED"))
                .andExpect(jsonPath("$.results[1].outcome").value("IGNORED"));
    }

    @Test
    void malformedEventsAreRejectedBeforeTheService() throws Exception {
        String[] bad = {
                "{}", "{\"events\":[]}", "{\"events\":[{\"commandId\":\"\",\"type\":\"SENT\"}]}",
                "{\"events\":[{\"commandId\":\"a\",\"type\":\"POLLED\"}]}", "{\"events\":[{\"commandId\":\"a\"}]}",
                "{\"events\":[null]}"};
        for (String body : bad) {
            mvc.perform(post("/api/v1/gateway/events").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_GATEWAY_EVENT"));
        }
        StringBuilder many = new StringBuilder("{\"events\":[");
        for (int i = 0; i < 101; i++) {
            many.append(i == 0 ? "" : ",").append("{\"commandId\":\"c").append(i).append("\",\"type\":\"SENT\"}");
        }
        mvc.perform(post("/api/v1/gateway/events").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content(many.append("]}").toString()))
                .andExpect(status().isBadRequest());
        verify(gateway, never()).report(any(), any());
    }
}
