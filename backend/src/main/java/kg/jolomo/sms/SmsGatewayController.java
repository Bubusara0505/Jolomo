package kg.jolomo.sms;

import java.util.List;
import kg.jolomo.shared.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Маршруты устройства шлюза. Авторизация: заголовок Authorization: Bearer токен устройства (куки здесь нет).
 * Ответы с телефонами и текстом SMS не кэшируются. Существуют только при jolomo.sms.gateway=android.
 */
@RestController
@RequestMapping("/api/v1/gateway")
@ConditionalOnProperty(name = "jolomo.sms.gateway", havingValue = "android")
class SmsGatewayController {

    record PollRequest(Integer limit) {}

    record PollResponse(List<SmsGatewayService.Leased> messages) {}

    record EventItem(String commandId, String type, String reason) {}

    record EventsRequest(List<EventItem> events) {}

    record EventsResponse(List<SmsGatewayService.EventResult> results) {}

    private static final int MAX_EVENTS = 100;

    private final GatewayDevices devices;
    private final SmsGatewayService gateway;

    SmsGatewayController(GatewayDevices devices, SmsGatewayService gateway) {
        this.devices = devices;
        this.gateway = gateway;
    }

    @PostMapping("/poll")
    ResponseEntity<PollResponse> poll(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) PollRequest body) {
        String device = authenticate(authorization);
        int limit = body == null || body.limit() == null ? 20 : body.limit();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PollResponse(gateway.poll(device, limit)));
    }

    @PostMapping("/events")
    ResponseEntity<EventsResponse> events(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) EventsRequest body) {
        String device = authenticate(authorization);
        if (body == null || body.events() == null || body.events().isEmpty() || body.events().size() > MAX_EVENTS) {
            throw invalid();
        }
        List<SmsGatewayService.DeviceEvent> events = body.events().stream().map(e -> {
            if (e == null || e.commandId() == null || e.commandId().isBlank() || e.commandId().length() > 200
                    || e.type() == null || !SmsGatewayService.EVENT_TYPES.contains(e.type())) {
                throw invalid();
            }
            return new SmsGatewayService.DeviceEvent(e.commandId(), e.type(), e.reason());
        }).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new EventsResponse(gateway.report(device, events)));
    }

    private String authenticate(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : null;
        return devices.authenticate(token)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "GATEWAY_UNAUTHORIZED", "error.gateway.unauthorized"));
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GATEWAY_EVENT", "error.gateway.invalid_event");
    }
}
