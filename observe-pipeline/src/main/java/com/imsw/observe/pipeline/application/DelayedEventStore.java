package com.imsw.observe.pipeline.application;

import java.time.Duration;

/**
 * 延时事件调度端口（application 层）。纯调度原语：按 correlationKey 调度/取消延时任务。
 *
 * <p>domain 逻辑（correlationKey 提取、DELAYED 包装 event 构造）由 {@link DelayedActionHandler}
 * 负责；本端口只管"到点执行 task"。实现见
 * {@link com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore}（SES + map）。
 * 未来换 Redis/DB 持久化实现时，handler 不变。
 */
public interface DelayedEventStore {

    /** 按 delay 延时执行 fireTask；同 correlationKey 的老 task cancel(false)（Replace 语义）。 */
    void schedule(String correlationKey, Runnable fireTask, Duration delay);

    /** 移除并 cancel(false) correlationKey 的 task；无则 no-op（幂等）。 */
    void cancel(String correlationKey);

    /** 当前待 fire 任务数。 */
    int pendingCount();

    /** 关闭调度器 + 清空待 fire 任务。 */
    void shutdown();
}
