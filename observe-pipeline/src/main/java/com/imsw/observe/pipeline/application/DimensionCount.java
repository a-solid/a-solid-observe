package com.imsw.observe.pipeline.application;

/**
 * 聚合统计投影（B6）：「维度 → 计数」。
 *
 * <p>JPQL constructor expression：{@code select new ...DimensionCount(e.status, count(e))}。
 */
public record DimensionCount(String dimension, long count) {}
