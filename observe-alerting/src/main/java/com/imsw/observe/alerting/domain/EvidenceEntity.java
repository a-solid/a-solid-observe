package com.imsw.observe.alerting.domain;

import java.time.Instant;

/**
 * 证据领域实体（ADR-0005 §2：1:N —— 一个 alert 可有多条 evidence，每次 emit（含 dedup 命中）各写一条）。
 *
 * <p>内容载荷 = {@code triggerEvent}（触发事件 JSON 快照，排错用）。历史版本的 {@code outputs}（nodeOutputs）
 * 因 node 输出链零调用恒空，已替换。
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
        String triggerEvent,
        String traceId,
        String spanId,
        Instant capturedAt,
        boolean truncated,
        int emitSeq) {}
