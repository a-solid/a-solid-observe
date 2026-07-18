package com.imsw.observe.controlplane.interfaces.dto;

import com.imsw.observe.pipeline.domain.FailedExecution;

public record FailedExecutionDto(
        Long id,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        Long executionId,
        String triggerType,
        String triggerEvent,
        Long subscriptionId,
        String nodeName,
        String errorType,
        String errorMessage,
        String stackTrace,
        String status,
        java.time.Instant createdAt,
        java.time.Instant resolvedAt) {

    public static FailedExecutionDto from(final FailedExecution e) {
        if (e == null) {
            return null;
        }
        return new FailedExecutionDto(
                e.id(),
                e.namespace(),
                e.pipelineId(),
                e.pipelineVersion(),
                e.executionId(),
                e.triggerType(),
                e.triggerEvent(),
                e.subscriptionId(),
                e.nodeName(),
                e.errorType(),
                e.errorMessage(),
                e.stackTrace(),
                e.status(),
                e.createdAt(),
                e.resolvedAt());
    }
}
