package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.alerting.domain.EvidenceEntity;

public record EvidenceDto(
        String alertId,
        String pipelineId,
        int pipelineVersion,
        String executionId,
        String nodeName,
        Map<String, Object> outputs,
        String traceId,
        String spanId,
        Instant capturedAt,
        boolean truncated) {

    public static EvidenceDto from(final EvidenceEntity e) {
        return new EvidenceDto(
                e.alertId(),
                e.pipelineId(),
                e.pipelineVersion(),
                e.executionId(),
                e.nodeName(),
                e.outputs(),
                e.traceId(),
                e.spanId(),
                e.capturedAt(),
                e.truncated());
    }
}
