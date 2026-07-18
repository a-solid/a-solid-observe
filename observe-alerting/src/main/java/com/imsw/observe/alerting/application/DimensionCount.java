package com.imsw.observe.alerting.application;

/**
 * 聚合统计投影（B6）：「维度 → 计数」。用于 bySeverity / byStatus 等。
 *
 * <p>JPQL constructor expression：{@code select new ...DimensionCount(a.severity, count(a))}。
 */
public record DimensionCount(String dimension, long count) {}
