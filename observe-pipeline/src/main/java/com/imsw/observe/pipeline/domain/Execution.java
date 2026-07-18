package com.imsw.observe.pipeline.domain;

import java.time.Instant;

public record Execution(
        Long id,
        Long pipelineId,
        int pipelineVersion,
        String team,
        String application,
        String triggerType,
        String triggerEvent,
        Long subscriptionId,
        String status,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String traceId,
        Instant createdAt) {}
