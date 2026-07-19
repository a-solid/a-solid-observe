package com.imsw.observe.controlplane.interfaces.dto;

import com.imsw.observe.pipeline.domain.Execution;

/**
 * 执行 DTO（合表后单表）：失败专属字段（executionId/nodeName/errorType/errorMessage/stackTrace）可空，
 * 仅 status=FAILED 行填充。
 */
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
        java.time.Instant createdAt,
        Long executionId,
        String nodeName,
        String errorType,
        String errorMessage,
        String stackTrace) {

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
                e.createdAt(),
                e.executionId(),
                e.nodeName(),
                e.errorType(),
                e.errorMessage(),
                e.stackTrace());
    }
}
