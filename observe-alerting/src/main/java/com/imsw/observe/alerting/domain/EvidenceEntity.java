package com.imsw.observe.alerting.domain;

import java.time.Instant;
import java.util.Map;

public record EvidenceEntity(
        String alertId,
        String pipelineId,
        int pipelineVersion,
        String executionId,
        String nodeName,
        Map<String, Object> outputs,
        String traceId,
        String spanId,
        Instant capturedAt,
        boolean truncated) {}
