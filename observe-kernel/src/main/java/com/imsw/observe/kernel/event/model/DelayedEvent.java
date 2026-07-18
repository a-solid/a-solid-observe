package com.imsw.observe.kernel.event.model;

import java.time.Instant;

/**
 * 延时重放事件。由 {@code InMemoryDelayedEventStore.fire}（经
 * {@code DelayedActionHandler.wrapAsDelayed}）产出，绕过 SourceDispatcher 直调 PipelineRunner。
 *
 * <p>嵌套 {@code originalEvent}（通常为 {@link CdcEvent}）——递归 sealed，序列化时需展开
 * 嵌套 Event（见 {@link Event} 上的 Jackson 多态注解，{@code @JsonSubTypes} 覆盖所有子类型，
 * 嵌套字段自动按子类型名 round-trip）。
 *
 * @param meta           {@link DelayedMeta}（含 schedule_id / subscription_id / scheduled_at
 *                       / fired_at / correlation_key，原 {@code original_event} key 已提升为本字段）
 * @param originalEvent  被延时重放的原始事件
 * @param sourceTs       实际 fire 时间
 */
public record DelayedEvent(DelayedMeta meta, Event originalEvent, Instant sourceTs) implements Event {}
