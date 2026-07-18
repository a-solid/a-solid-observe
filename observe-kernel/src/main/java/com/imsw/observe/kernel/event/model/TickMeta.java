package com.imsw.observe.kernel.event.model;

import java.util.Map;

/**
 * {@link TickEvent} 的元数据（Cron 触发）。sourceType 由子类型隐式 = CRON，故不再单独字段（ADR-0006）。
 *
 * <p>{@code cronName}/{@code cronExpression} 为 B4 CronSource 预留：B3 CronSource 传自己的 name
 * 和（如尚无表达式）null 占位即可。
 *
 * @param source         Cron 来源标识（cron name）
 * @param cronName       Cron 任务名（与 source 通常一致；显式字段便于脚本/索引）
 * @param cronExpression Cron 表达式（可为 null，B4 完善前 CronSource 可能仅传 name）
 * @param attributes     附加属性
 */
public record TickMeta(String source, String cronName, String cronExpression, Map<String, Object> attributes) {}
