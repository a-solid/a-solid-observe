package com.imsw.observe.kernel.event.model;

import java.util.Map;

/**
 * {@link TickEvent} 的元数据（Cron 触发）。sourceType 由子类型隐式 = CRON，故不再单独字段（ADR-0006）。
 *
 * <p>由 {@code CronScheduler}（ADR-0007 B4）产出：{@code cronName}/{@code cronExpression} 为真实值，
 * {@code source} = 订阅在 {@code Snapshot.subscriptionsBySource} 的索引键（= sub.source().mq()，
 * 通常等于 cronName）——matcher 按此路由（见 {@code DefaultSubscriptionMatcher.matchesNamed}）。
 *
 * @param source         Cron 来源标识（cron name；必须等于订阅索引键）
 * @param cronName       Cron 任务名（与 source 通常一致；显式字段便于脚本/索引）
 * @param cronExpression Cron 表达式（Spring 6 字段；可为 null 的历史占位已被 B4 取代）
 * @param attributes     附加属性
 */
public record TickMeta(String source, String cronName, String cronExpression, Map<String, Object> attributes) {}
