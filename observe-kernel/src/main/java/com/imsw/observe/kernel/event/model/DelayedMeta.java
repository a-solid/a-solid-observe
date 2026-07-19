package com.imsw.observe.kernel.event.model;

import java.util.Map;

/**
 * {@link DelayedEvent} 的元数据。sourceType 由子类型隐式 = DELAYED，故不再单独字段（ADR-0006）。
 *
 * <p>{@code subscriptionId} 是一等路由键——matcher 按 subscriptionId 在
 * {@code PipelineRegistry.Snapshot.subscriptionsById} 直查回原订阅（与 CdcMeta 的 db/table、
 * TickMeta/ApiMeta 的 source 对称）。fire 到点经 {@code SourceDispatcher.onEvent} 回流，由 matcher
 * 路由后扇出 N 个 pipeline（不再 bypass matcher）。
 *
 * <p>{@code attributes} 保留延时语义 key（向后兼容脚本/审计读取，但不再作为路由来源）：
 * {@code schedule_id} / {@code scheduled_at} / {@code fired_at} / {@code correlation_key}。
 *
 * @param subscriptionId 原订阅 id（matcher 路由键）
 * @param attributes     附加属性（schedule 审计/排错字段）
 */
public record DelayedMeta(Long subscriptionId, Map<String, Object> attributes) {}
