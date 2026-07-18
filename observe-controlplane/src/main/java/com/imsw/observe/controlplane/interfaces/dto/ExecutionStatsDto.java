package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.pipeline.application.ExecutionStats;

/** 执行统计 + 成功率响应（B6）。 */
public record ExecutionStatsDto(
        String namespace,
        Instant from,
        Instant to,
        Map<String, Long> byStatus,
        long total,
        long failedCount,
        double successRate) {

    public static ExecutionStatsDto from(final ExecutionStats s) {
        return new ExecutionStatsDto(
                s.namespace(), s.from(), s.to(), s.byStatus(), s.total(), s.failedCount(), s.successRate());
    }
}
