package com.imsw.observe.kernel.event.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 事件契约（sealed interface + per-source 子类型）。参见 ADR-0006。
 *
 * <p>各 source 的事件形态差异巨大（CDC 有 before/after/op；Cron 仅触发无 payload；Api 有 HTTP body；
 * Delayed 嵌套原事件），用单一 record + 可空字段表达不清，故改为 sealed interface，每 source 一个子类型，
 * 类型安全 + 编译期区分 + 新 source 数据有正经载体。
 *
 * <p>各子类型的 {@code Meta} 是独立的 top-level record（{@link CdcMeta}/{@link TickMeta}/
 * {@link ApiMeta}/{@link DelayedMeta}），不再共用旧 {@code Event.EventMeta}。sourceType 不再
 * 冗余挂在 meta 上——子类型本身即隐含 sourceType。
 *
 * <h2>Jackson 多态序列化</h2>
 *
 * trigger_event / evidence 等字段会把 {@code Event} 序列化为 JSON
 * （见 {@link ExecutionMeta#triggerEvent()}）。
 * 通过 {@code @JsonTypeInfo(Id.NAME, property="@type")} + {@code @JsonSubTypes} 列出全部 4 个子类型，
 * Jackson 在序列化时写出 {@code "@type": "CdcEvent"|...} 标识，反序列化时按标识还原子类型；
 * {@link DelayedEvent#originalEvent()} 递归嵌套 {@code Event} 也按相同机制 round-trip。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CdcEvent.class, name = "CdcEvent"),
    @JsonSubTypes.Type(value = TickEvent.class, name = "TickEvent"),
    @JsonSubTypes.Type(value = ApiEvent.class, name = "ApiEvent"),
    @JsonSubTypes.Type(value = DelayedEvent.class, name = "DelayedEvent"),
})
public sealed interface Event permits CdcEvent, TickEvent, ApiEvent, DelayedEvent {

    /** Source 端时间戳（CDC 消息时间 / Cron 触发时间 / Api 接收时间 / Delayed 实际 fire 时间）。 */
    Instant sourceTs();

    /**
     * 该事件的触发源类型（ADR-0006：sourceType 隐含于子类型，本方法让它成为可复用的领域事实，
     * 取代散落在 registry/matcher/runner 各处的 instanceof 级联 re-derive）。
     *
     * <p>{@link DelayedEvent} 递归取 {@code originalEvent().sourceType()}——延时重放不是独立来源，
     * 重放 CDC 事件 → sourceType=CDC。其余子类型返回各自固定常量（{@link CdcEvent}→CDC、
     * {@link TickEvent}→CRON、{@link ApiEvent}→API）。
     *
     * <p>方法是 record 之外的派生属性，非 record 组件——不参与 Jackson 序列化（sourceType 由
     * {@code @type} 标识隐含，见 {@link EventJacksonRoundTripTest}）。
     */
    SourceType sourceType();
}
