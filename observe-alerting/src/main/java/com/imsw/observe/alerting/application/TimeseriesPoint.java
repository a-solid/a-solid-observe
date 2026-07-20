package com.imsw.observe.alerting.application;

import java.time.Instant;

/**
 * 时间序列点（B6）：桶起点 + 计数 + 严重级别，供前端折线/柱状图。
 *
 * @param bucketStart 桶起点（UTC，1h 桶为整点、1d 桶为当日 00:00）
 * @param count 该桶命中行数（缺桶已补零）
 * @param severity 告警严重级别（CRITICAL/WARNING/INFO）
 */
public record TimeseriesPoint(Instant bucketStart, long count, String severity) {}
