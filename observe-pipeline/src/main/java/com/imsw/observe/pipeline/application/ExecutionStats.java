package com.imsw.observe.pipeline.application;

import java.time.Instant;
import java.util.Map;

/**
 * 执行统计结果（B6，合表后单表口径）：某 namespace 在 {@code [from, to)} 窗口内的执行计数 + 成功率。
 *
 * <p>{@code byStatus}（SUCCESS/SHORT_CIRCUITED/FAILED）与 {@code total} 全来自 {@code executions} 单表
 * （按 {@code started_at} 同窗口、全量行）。{@code failedCount} 即 {@code byStatus["FAILED"]}。
 * 单表全量行 → 无跨表采样偏差。
 *
 * @param successRate {@code (total - failedCount) / total}；分母 0 返回 1.0（无执行视为健康）
 */
public record ExecutionStats(
        String namespace,
        Instant from,
        Instant to,
        Map<String, Long> byStatus,
        long total,
        long failedCount,
        double successRate) {}
