package com.imsw.observe.controlplane.interfaces;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
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
    public SubmitEventResponse submit(@RequestBody final SubmitEventRequest req) {
        if (req.source() == null || req.source().isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        Op op = req.op() == null ? Op.INSERT : Op.valueOf(req.op());
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> attributes = new HashMap<>();
        if (req.attributes() != null) {
            attributes.putAll(req.attributes());
        }
        attributes.put("eventId", eventId);

        Event event = new Event(
                new Event.EventMeta(SourceType.API, req.source(), null, req.table(), attributes),
                req.before(),
                req.after(),
                op,
                Instant.now());

        apiSource.submit(event);
        return new SubmitEventResponse(eventId);
    }

    public record SubmitEventRequest(
            String source,
            String table,
            String op,
            Map<String, Object> before,
            Map<String, Object> after,
            Map<String, Object> attributes) {}

    public record SubmitEventResponse(String eventId) {}
}
