package com.imsw.observe.pipeline.domain;

import java.time.Instant;

public record FailedExecution(
        String id,
        String pipelineId,
        int pipelineVersion,
        String executionId,
        String team,
        String application,
        String triggerType,
        String triggerEvent,
        String subscriptionId,
        String nodeName,
        String errorType,
        String errorMessage,
        String stackTrace,
        String status,
        Instant createdAt,
        Instant resolvedAt) {}
