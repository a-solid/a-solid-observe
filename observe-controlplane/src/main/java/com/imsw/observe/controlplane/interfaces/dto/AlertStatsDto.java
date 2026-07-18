package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.alerting.application.AlertStats;

/** 告警统计响应（B6）。 */
public record AlertStatsDto(
        String namespace,
        Instant from,
        Instant to,
        Map<String, Long> bySeverity,
        Map<String, Long> byStatus,
        long total) {

    public static AlertStatsDto from(final AlertStats s) {
        return new AlertStatsDto(s.namespace(), s.from(), s.to(), s.bySeverity(), s.byStatus(), s.total());
    }
}
