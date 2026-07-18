package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.alerting.domain.EvidenceEntity;

public record EvidenceDto(
        Long alertId,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        Long executionId,
        String nodeName,
        Map<String, Object> outputs,
        String traceId,
        String spanId,
        Instant capturedAt,
        boolean truncated) {

    public static EvidenceDto from(final EvidenceEntity e) {
        return new EvidenceDto(
                e.alertId(),
                e.namespace(),
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
