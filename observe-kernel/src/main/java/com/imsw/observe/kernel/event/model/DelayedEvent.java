package com.imsw.observe.kernel.event.model;

import java.time.Instant;

/**
 * 延时重放事件。由 {@code DelayedActionHandler.fire}（到点回调）构造，经
 * {@code SourceDispatcher.onEvent} 回流——与 CdcEvent/TickEvent/ApiEvent 一样走 matcher，
 * 按 {@link DelayedMeta#subscriptionId()} 路由回原订阅扇出（见 ADR-0006 addendum）。
 *
 * <p>嵌套 {@code originalEvent}（通常为 {@link CdcEvent}）——递归 sealed，序列化时需展开
 * 嵌套 Event（见 {@link Event} 上的 Jackson 多态注解，{@code @JsonSubTypes} 覆盖所有子类型，
 * 嵌套字段自动按子类型名 round-trip）。
 *
 * @param meta           {@link DelayedMeta}（subscriptionId 路由键 + correlationKey 业务键）
 * @param originalEvent  被延时重放的原始事件
 * @param sourceTs       实际 fire 时间
 */
public record DelayedEvent(DelayedMeta meta, Event originalEvent, Instant sourceTs) implements Event {

    /**
     * 延时重放非独立来源——递归取 {@code originalEvent().sourceType()}（重放 CDC → CDC）。
     * 与 {@code DefaultPipelineRunner} 旧 {@code sourceTypeOf} 的递归语义一致，已上提到类型自身。
     */
    @Override
    public SourceType sourceType() {
        return originalEvent().sourceType();
    }
}
