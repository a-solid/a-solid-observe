package com.imsw.observe.kernel.event.model;

import java.time.Instant;

/**
 * Cron 触发的"纯信号"事件。由 {@code CronScheduler}（ADR-0007 B4，每订阅一调度）到点产出。
 *
 * <p>无 payload：pipeline 脚本通过节点里的 {@code db.queryOne} 主动查询 DB（ADR-0006 §3）。
 *
 * @param meta     {@link TickMeta}（含 cron name / cron 表达式 / attributes）
 * @param sourceTs 触发时刻
 */
public record TickEvent(TickMeta meta, Instant sourceTs) implements Event {}
