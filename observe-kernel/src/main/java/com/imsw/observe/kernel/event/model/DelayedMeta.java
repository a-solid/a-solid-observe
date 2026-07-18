package com.imsw.observe.kernel.event.model;

import java.util.Map;

/**
 * {@link DelayedEvent} 的元数据。sourceType 由子类型隐式 = DELAYED，故不再单独字段（ADR-0006）。
 *
 * <p>{@code attributes} 必须沿用旧 {@code DelayedActionHandler.wrapAsDelayed} 的 key 集合，以保留行为：
 * {@code schedule_id} / {@code subscription_id} / {@code scheduled_at}
 * / {@code fired_at} / {@code correlation_key}。
 * 注意：{@code original_event} 不再作为 attributes key 写入——它现在是
 * {@link DelayedEvent#originalEvent()} 的一等字段（由 B3 Task 4 在 {@code DelayedActionHandler}
 * 中移除冗余的 attributes key）。
 *
 * @param source     来源标识（约定 {@code "delayed:" + subscriptionId}）
 * @param attributes 附加属性（包含上述延时语义 key）
 */
public record DelayedMeta(String source, Map<String, Object> attributes) {}
