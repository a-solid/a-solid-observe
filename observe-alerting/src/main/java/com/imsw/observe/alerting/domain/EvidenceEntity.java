package com.imsw.observe.alerting.domain;

import java.time.Instant;
import java.util.Map;

public record EvidenceEntity(
        Long alertId,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        Long executionId,
        String nodeName,
        Map<String, Object> outputs,
        String traceId,
        String spanId,
        Instant capturedAt,
        boolean truncated) {}
