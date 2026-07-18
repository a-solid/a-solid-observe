package com.imsw.observe.alerting.infrastructure.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imsw.observe.alerting.domain.EvidenceEntity;
import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionMeta;

public final class EvidenceCollector {

    private static final int MAX_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;

    public EvidenceCollector(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Collected collect(final AlertSignal signal, final ExecutionContext ctx) {
        ExecutionMeta meta = ctx.meta();
        Map<String, Object> outputs = collectOutputs(signal.evidence(), ctx.nodeOutputs());
        Sized sized = enforceLimit(outputs);
        EvidenceEntity entity = new EvidenceEntity(
                null,
                meta.namespace(),
                meta.pipelineId(),
                meta.pipelineVersion(),
                meta.executionId(),
                null,
                sized.outputs,
                meta.traceId(),
                meta.spanId(),
                Instant.now(),
                sized.truncated);
        return new Collected(entity, sized.sizeBytes, sized.truncated);
    }

    private Map<String, Object> collectOutputs(
            final AlertSignal.EvidenceSpec spec, final Map<String, Map<String, Object>> nodeOutputs) {
        if (nodeOutputs == null || nodeOutputs.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        boolean attachOutputs = spec == null || spec.attachOutputs();
        if (attachOutputs) {
            nodeOutputs.forEach((node, kvs) -> {
                if (kvs != null && !kvs.isEmpty()) {
                    result.put(node, new LinkedHashMap<>(kvs));
                }
            });
        }
        Set<String> capture = captureKeys(spec);
        if (!capture.isEmpty()) {
            Map<String, Object> captured = collectCaptured(capture, nodeOutputs);
            if (!captured.isEmpty()) {
                result.put("_captured", captured);
            }
        }
        return result;
    }

    private static Set<String> captureKeys(final AlertSignal.EvidenceSpec spec) {
        if (spec == null || spec.capture() == null) {
            return Set.of();
        }
        return Set.copyOf(spec.capture());
    }

    private static Map<String, Object> collectCaptured(
            final Set<String> capture, final Map<String, Map<String, Object>> nodeOutputs) {
        Map<String, Object> captured = new LinkedHashMap<>();
        for (String key : capture) {
            nodeOutputs.forEach((node, kvs) -> {
                if (kvs != null && kvs.containsKey(key)) {
                    captured.put(key, kvs.get(key));
                }
            });
        }
        return captured;
    }

    private Sized enforceLimit(final Map<String, Object> outputs) {
        int size = measure(outputs);
        if (size <= MAX_BYTES) {
            return new Sized(outputs, size, false);
        }
        Map<String, Object> trimmed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : outputs.entrySet()) {
            trimmed.put(entry.getKey(), entry.getValue());
            int measured = measure(trimmed);
            if (measured > MAX_BYTES) {
                trimmed.remove(entry.getKey());
                trimmed.put("_truncated", List.of("size_limit_exceeded"));
                break;
            }
        }
        return new Sized(trimmed, measure(trimmed), true);
    }

    private int measure(final Object value) {
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (JsonProcessingException e) {
            return 0;
        }
    }

    private record Sized(Map<String, Object> outputs, int sizeBytes, boolean truncated) {}

    public record Collected(EvidenceEntity entity, int sizeBytes, boolean truncated) {}
}
