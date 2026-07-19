package com.imsw.observe.pipeline.domain.subscription;

import java.time.Duration;

/**
 * 订阅级 action（delayed-redesign spec D1/D2）。
 *
 * <p>三种 actionType 平权，每个订阅选其一：
 * <ul>
 *   <li>{@link Run}：事件到达 → 立即扇出 N 个 pipeline（默认）。</li>
 *   <li>{@link Schedule}：事件到达 → 安排一个未来执行的延迟任务；到期 fire 时 DelayedEvent 重放，
 *       matcher 重新匹配订阅、扇出（与 ADR-0006 §9.2 一致）。</li>
 *   <li>{@link Cancel}：事件到达 → 撤销 namespace 内 correlationKey 匹配的延迟任务。</li>
 * </ul>
 *
 * <p>schedule/cancel 不强制配对——业务方通过 keyPath 运行期对齐（D3）。
 */
public sealed interface Action permits Action.Run, Action.Schedule, Action.Cancel {

    record Run() implements Action {}

    record Schedule(Duration delay, String correlationKeyPath) implements Action {}

    record Cancel(String correlationKeyPath) implements Action {}
}
