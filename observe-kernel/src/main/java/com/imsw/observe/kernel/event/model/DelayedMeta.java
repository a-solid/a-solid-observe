package com.imsw.observe.kernel.event.model;

/**
 * {@link DelayedEvent} 的元数据。sourceType 由子类型隐式 = DELAYED，故不再单独字段（ADR-0006）。
 *
 * <p>两个字段都是"业务路由/标识键"——不含 schedule_id/scheduled_at/fired_at 等基础设施
 * 副产物（审计需求出现时再加，YAGNI）：
 * <ul>
 *   <li>{@code subscriptionId}：matcher 路由键——按此在
 *       {@code PipelineRegistry.Snapshot.subscriptionsById} 直查回原订阅。</li>
 *   <li>{@code correlationKey}：用户配置的 schedule/cancel 配对业务键（来自
 *       {@code Action.Schedule.correlationKeyPath} 抽取结果）。namespace 级 key 拼装在
 *       {@code DelayedActionHandler} 完成，本字段保留 raw key 用于脚本/排错。</li>
 * </ul>
 *
 * @param subscriptionId 原订阅 id（matcher 路由键）
 * @param correlationKey raw 业务键（无 namespace 前缀；可能为 null）
 */
public record DelayedMeta(Long subscriptionId, String correlationKey) {}
