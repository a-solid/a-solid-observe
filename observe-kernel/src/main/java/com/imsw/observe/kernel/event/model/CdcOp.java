package com.imsw.observe.kernel.event.model;

/**
 * CDC 数据变更操作类型（仅适用于 {@link CdcEvent}）。
 *
 * <p>区别于旧 {@code Op} 枚举：{@code TICK}/{@code API}/{@code DELAYED} 不再是 op，
 * 而是由 {@link Event} 的子类型（{@link TickEvent}/{@link ApiEvent}/{@link DelayedEvent}）本身表达。
 * 参见 ADR-0006。
 */
public enum CdcOp {
    INSERT,
    UPDATE,
    DELETE
}
