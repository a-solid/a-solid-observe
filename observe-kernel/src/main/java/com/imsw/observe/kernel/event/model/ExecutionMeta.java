package com.imsw.observe.kernel.event.model;

import java.time.Instant;
import java.util.Map;

public record ExecutionMeta(
        String executionId,
        String pipelineId,
        int pipelineVersion,
        String team,
        String application,
        Map<String, String> pipelineLabels,
        String traceId,
        String spanId,
        SourceType triggerType,
        Event triggerEvent,
        Instant triggeredAt,
        String subscriptionId) {}
