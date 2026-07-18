package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.kernel.alert.model.Severity;

public record AlertDto(
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
        String status,
        int dedupCount,
        String traceId) {

    public static AlertDto from(final AlertEntity e) {
        return new AlertDto(
                e.id(),
                e.team(),
                e.application(),
                e.pipelineLabels(),
                e.pipelineId(),
                e.pipelineVersion(),
                e.executionId(),
                e.fingerprint(),
                e.severity(),
                e.labels(),
                e.annotations(),
                e.startsAt(),
                e.lastSeenAt(),
                e.endsAt(),
                e.resolvedAt(),
                e.status() == null ? null : e.status().name(),
                e.dedupCount(),
                e.traceId());
    }
}
