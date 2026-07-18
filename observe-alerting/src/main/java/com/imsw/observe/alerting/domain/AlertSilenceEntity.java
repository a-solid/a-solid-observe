package com.imsw.observe.alerting.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Silence 规则（ADR-0005 §3）：在 {@code [startsAt, endsAt]} 生效期内，emit 时命中匹配维度则不建告警。
 *
 * @param matchType 匹配维度类型
 * @param match 匹配载荷（结构随 matchType：FINGERPRINT={fingerprint}；LABELS={labels 子集}；PIPELINE={pipelineId}）
 */
public record AlertSilenceEntity(
        Long id,
        String namespace,
        AlertSilenceMatchType matchType,
        Map<String, Object> match,
        Instant startsAt,
        Instant endsAt,
        String note,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {}
