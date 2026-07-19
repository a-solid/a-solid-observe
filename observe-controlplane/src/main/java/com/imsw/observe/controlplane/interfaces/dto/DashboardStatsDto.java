package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 看板聚合统计响应（B9 dashboard）：一次性返回 dashboard 页面所需的全部标量聚合，避免前端发 6+ 个 round-trip。
 *
 * <p><b>纯标量</b>：不含 sparkline / trend / throughput 时序数组——这些走独立 timeseries 端点（按需、可缓存）。
 * 本端点只回当前的 "bySeverity/byStatus/topN" 聚合 + hero 数字（eventsToday/alertsToday）。
 *
 * <p>区间语义：{@code from}/{@code to} 缺省时由 service 归一化为 "今天 0:00 ~ 现在"；hero 数字直接复用区间 total
 * （当区间 = 今天时即"今天的 events/alerts"）。
 *
 * @param alertsBySeverity CRITICAL/WARNING/INFO → 计数
 * @param alertsByStatus ACTIVE/EXPIRED → 计数
 * @param executionsByStatus SUCCESS/SHORT_CIRCUITED/FAILED → 计数
 * @param teamDist label_team → 计数（top N）
 * @param topPipelines pipelineId + name → 计数（top N；name 由运行态 registry 查，未加载用占位）
 * @param topFingerprints fingerprint → 计数（top N）
 */
public record DashboardStatsDto(
        String namespace,
        Instant from,
        Instant to,
        Map<String, Long> alertsBySeverity,
        Map<String, Long> alertsByStatus,
        long alertsTotal,
        Map<String, Long> executionsByStatus,
        long executionsTotal,
        long executionsFailed,
        double executionsSuccessRate,
        long eventsToday,
        long alertsToday,
        List<DimensionCountDto> teamDist,
        List<PipelineCountDto> topPipelines,
        List<DimensionCountDto> topFingerprints) {

    /** 通用维度计数（dimension 字符串 → 计数）。用于 teamDist 与 topFingerprints。 */
    public record DimensionCountDto(String dimension, long count) {}

    /** pipeline 维度计数（多带 pipelineName —— 由 service 层从运行态 registry 查得）。 */
    public record PipelineCountDto(Long pipelineId, String pipelineName, long count) {}
}
