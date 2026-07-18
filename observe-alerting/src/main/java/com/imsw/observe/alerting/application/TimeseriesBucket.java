package com.imsw.observe.alerting.application;

/**
 * 时间序列桶投影（B6）：按 {@code EXTRACT(YEAR/MONTH/DAY/HOUR FROM ts)} 聚合的原始结果。
 *
 * <p>{@code hour} 对 {@code 1d} 桶无意义（service 侧忽略）；{@code count} 为该桶命中行数。
 * service 侧把 (year,month,day,hour) 重建为 {@code Instant} 桶起点，并对缺桶补零。
 */
public record TimeseriesBucket(int year, int month, int day, int hour, long count) {}
