package com.imsw.observe.alerting.domain;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.kernel.alert.model.Severity;

/**
 * 告警领域实体（ADR-0005 两维分离；ADR-0004 label 投影）。
 *
 * <p>B9 / ADR-0004：team/application/pipelineLabels 一等字段移除，维度统一进 {@code labels}（Map）；
 * {@code labelTeam}/{@code labelApp}/{@code labelLine} 是 labels 的索引投影列（从 labels.get(team/app/line) 取），
 * 缺失为 null。namespace 仍是一等列（ADR-0002 顶层隔离铁律）。
 *
 * <p>ADR-0005 两维分离：{@code status}（系统态 ACTIVE/EXPIRED）与 {@code disposition}（用户处置
 * NONE/ACKNOWLEDGED/IGNORED）正交独立。
 *
 * @param ackNote ack/ignore 附注（disposition）
 * @param ackBy 操作人（一期从请求体取，无鉴权）
 * @param ackAt ack/ignore 时间
 */
public record AlertEntity(
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
        AlertStatus status,
        AlertDisposition disposition,
        int dedupCount,
        String ackNote,
        String ackBy,
        Instant ackAt,
        String traceId,
        String labelTeam,
        String labelApp,
        String labelLine) {}
