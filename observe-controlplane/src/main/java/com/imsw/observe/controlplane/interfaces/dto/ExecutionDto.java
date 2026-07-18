package com.imsw.observe.controlplane.interfaces.dto;

import com.imsw.observe.pipeline.domain.Execution;

public record ExecutionDto(
        Long id,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        String triggerType,
        String triggerEvent,
        Long subscriptionId,
        String status,
        java.time.Instant startedAt,
        java.time.Instant endedAt,
        Long durationMs,
        String traceId,
        java.time.Instant createdAt) {

    public static ExecutionDto from(final Execution e) {
        if (e == null) {
            return null;
        }
        return new ExecutionDto(
                e.id(),
                e.namespace(),
                e.pipelineId(),
                e.pipelineVersion(),
                e.triggerType(),
                e.triggerEvent(),
                e.subscriptionId(),
                e.status(),
                e.startedAt(),
                e.endedAt(),
                e.durationMs(),
                e.traceId(),
                e.createdAt());
    }
}
