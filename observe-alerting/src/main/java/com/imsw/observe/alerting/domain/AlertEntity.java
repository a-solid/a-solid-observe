package com.imsw.observe.alerting.domain;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.kernel.alert.model.Severity;

public record AlertEntity(
        Long id,
        String team,
        String application,
        Map<String, String> pipelineLabels,
        Long pipelineId,
        int pipelineVersion,
        Long executionId,
        String fingerprint,
        Severity severity,
        Map<String, String> labels,
        Map<String, String> annotations,
        Instant startsAt,
        Instant lastSeenAt,
        Instant endsAt,
        Instant resolvedAt,
        AlertStatus status,
        int dedupCount,
        String generatorURL,
        String traceId) {}
