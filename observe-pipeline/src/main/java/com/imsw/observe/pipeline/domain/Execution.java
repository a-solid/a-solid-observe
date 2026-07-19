package com.imsw.observe.pipeline.domain;

import java.time.Instant;

/**
 * 一次 pipeline 执行的概要行（合表后，原 executions + failed_executions 合一）。
 *
 * <p>{@code status} 表结果：{@code SUCCESS} / {@code SHORT_CIRCUITED} / {@code FAILED}。
 * 失败专属字段（{@code executionId}/{@code nodeName}/{@code errorType}/{@code errorMessage}/
 * {@code stackTrace}）仅 {@code FAILED} 行填充，其余为 null。
 *
 * <p>粒度：一 pipeline 一行（recorder 在 pipeline 级调，非 node 级；node 级详情走 evidence）。
 *
 * @param executionId kernel ExecutionMeta.executionId（失败定位用，成功行可空）
 */
public record Execution(
        Long id,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        String triggerType,
        String triggerEvent,
        Long subscriptionId,
        String status,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String traceId,
        Instant createdAt,
        Long executionId,
        String nodeName,
        String errorType,
        String errorMessage,
        String stackTrace) {}
