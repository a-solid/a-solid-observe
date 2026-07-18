package com.imsw.observe.pipeline.domain;

import java.time.Instant;

public record Execution(
        Long id,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        String triggerType,
        String triggerEvent,
        Long subscriptionId,
        String status,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String traceId,
        Instant createdAt) {}
