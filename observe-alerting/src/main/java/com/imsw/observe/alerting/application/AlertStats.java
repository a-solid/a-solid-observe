package com.imsw.observe.alerting.application;

import java.time.Instant;
import java.util.Map;

/**
 * 告警统计结果（B6）：某 namespace 在 {@code [from, to)} 窗口内的计数聚合。
 *
 * @param namespace 软隔离维度
 * @param from 窗口起点（含）
 * @param to 窗口终点（不含）
 * @param bySeverity severity → 计数（INFO/WARNING/CRITICAL）
 * @param byStatus status → 计数（FIRING/RESOLVED；B7 后含 ACKNOWLEDGED/IGNORED）
 * @param total 窗口内告警总数
 */
public record AlertStats(
        String namespace,
        Instant from,
        Instant to,
        Map<String, Long> bySeverity,
        Map<String, Long> byStatus,
        long total) {}
