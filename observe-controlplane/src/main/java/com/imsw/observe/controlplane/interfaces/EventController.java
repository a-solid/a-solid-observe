package com.imsw.observe.controlplane.interfaces;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.kernel.event.model.ApiEvent;
import com.imsw.observe.kernel.event.model.ApiMeta;
import com.imsw.observe.pipeline.infrastructure.source.ApiSource;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final ApiSource apiSource;

    public EventController(final ApiSource apiSource) {
        this.apiSource = apiSource;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SubmitEventResponse> submit(@Valid @RequestBody final SubmitEventRequest req) {
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> attributes = new HashMap<>();
        if (req.attributes() != null) {
            attributes.putAll(req.attributes());
        }
        attributes.put("eventId", eventId);

        // ADR-0006 §3.5：ApiSource 产 ApiEvent。source 即 apiName；payload 为 HTTP body 数据字段，
        // 由调用方按需组装（默认透传请求 payload，无则空 Map）。
        Map<String, Object> payload = req.payload() == null ? Map.of() : Map.copyOf(req.payload());
        ApiMeta meta = new ApiMeta("api:" + req.source(), req.source(), Map.copyOf(attributes));
        ApiEvent event = new ApiEvent(meta, payload, Instant.now());

        apiSource.submit(event);
        return ApiResponse.ok(new SubmitEventResponse(eventId));
    }

    public record SubmitEventRequest(
            @NotBlank String source, Map<String, Object> payload, Map<String, Object> attributes) {}

    public record SubmitEventResponse(String eventId) {}
}
