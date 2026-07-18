package com.imsw.observe.alerting.domain;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.kernel.alert.model.Severity;

/**
 * 告警领域实体（ADR-0005）。
 *
 * @param ackNote ack/ignore 附注（disposition）
 * @param ackBy 操作人（一期从请求体取，无鉴权）
 * @param ackAt ack/ignore 时间
 */
public record AlertEntity(
        Long id,
        String namespace,
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
        String ackNote,
        String ackBy,
        Instant ackAt,
        String traceId) {}
