package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.kernel.alert.model.Severity;

/** 告警 DTO（含 ADR-0005 disposition 字段 ackNote/ackBy/ackAt；ADR-0004 label 投影字段）。 */
public record AlertDto(
        Long id,
        String namespace,
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
        String ackNote,
        String ackBy,
        Instant ackAt,
        String traceId,
        String labelTeam,
        String labelApp,
        String labelLine) {

    public static AlertDto from(final AlertEntity e) {
        return new AlertDto(
                e.id(),
                e.namespace(),
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
                e.ackNote(),
                e.ackBy(),
                e.ackAt(),
                e.traceId(),
                e.labelTeam(),
                e.labelApp(),
                e.labelLine());
    }
}
