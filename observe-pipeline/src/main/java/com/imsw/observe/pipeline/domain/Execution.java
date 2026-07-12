package com.imsw.observe.pipeline.domain;

import java.time.Instant;

public record Execution(
        String id,
        String pipelineId,
        int pipelineVersion,
        String team,
        String application,
        String triggerType,
        String triggerEvent,
        String subscriptionId,
        String status,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String traceId,
        Instant createdAt) {}
