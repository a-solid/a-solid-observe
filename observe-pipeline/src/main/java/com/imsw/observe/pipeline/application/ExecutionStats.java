package com.imsw.observe.pipeline.application;

import java.time.Instant;
import java.util.Map;

/**
 * 执行统计结果（B6）：某 namespace 在 {@code [from, to)} 窗口内的执行计数 + 成功率。
 *
 * <p>{@code byStatus}（SUCCESS/SHORT_CIRCUITED）与 {@code total} 来自 {@code executions}（按 {@code started_at}）；
 * {@code failedCount} 来自 {@code failed_executions}（按 {@code created_at}，同窗口）——两表无关联，分查相除。
 *
 * @param successRate {@code total / (total + failedCount)}；分母 0 返回 1.0（无执行视为健康）
 */
public record ExecutionStats(
        String namespace,
        Instant from,
        Instant to,
        Map<String, Long> byStatus,
        long total,
        long failedCount,
        double successRate) {}
