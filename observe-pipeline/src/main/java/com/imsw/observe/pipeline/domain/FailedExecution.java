package com.imsw.observe.pipeline.domain;

import java.time.Instant;

public record FailedExecution(
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
        Instant createdAt,
        Instant resolvedAt) {}
