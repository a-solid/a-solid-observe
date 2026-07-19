package com.imsw.observe.kernel.event.model;

import java.time.Instant;
import java.util.Map;

/**
 * {@link TickEvent} 的元数据（Cron 触发）。sourceType 由子类型隐式 = CRON，故不再单独字段（ADR-0006）。
 *
 * <p>由 {@code CronSource}（ADR-0007 + B9 §4）产出。{@code subscriptionId} 为 matcher 路由键——
 * 按 {@code Snapshot.subscriptionsById} 直查回原订阅（与 DelayedEvent/ApiEvent 对称，
 * 见 ADR-0007 addendum）。{@code cronExpression} 透传便于脚本/可观测。
 *
 * @param subscriptionId 原订阅 id（matcher 路由键）
 * @param cronExpression Cron 表达式（Spring 6 字段；触发该 TickEvent 的 cron，便于脚本/可观测）
 * @param firedAt        实际触发时刻（与 {@link TickEvent#sourceTs()} 冗余但便于不解析外层 record 的脚本读取）
 * @param attributes     附加属性（cron 来源一般无业务上下文，可空）
 */
public record TickMeta(Long subscriptionId, String cronExpression, Instant firedAt, Map<String, Object> attributes) {}
