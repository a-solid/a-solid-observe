package com.imsw.observe.alerting.domain;

import java.time.Instant;
import java.util.Map;

/**
 * 证据领域实体（ADR-0005 §2：1:N —— 一个 alert 可有多条 evidence，每次 emit（含 dedup 命中）各写一条）。
 *
 * @param id 自有 snowflake PK（不再以 alertId 作 PK）
 * @param alertId 引用所属 alert（普通列，非 PK）
 * @param emitSeq 该 alert 内的证据序号（首次 1，dedup 递增）
 */
public record EvidenceEntity(
        Long id,
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
        boolean truncated,
        int emitSeq) {}
