package com.imsw.observe.pipeline.application;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

/**
 * 延时事件重放端口（application 层）。
 *
 * <p>{@link DelayedActionHandler#fire} 到点时调用本端口，把"原订阅 + 包装后的 DelayedEvent"投回分发链路，
 * 让 dispatcher 直接按订阅级扇出 N 个 pipeline——**绕过 matcher**（匹配在 schedule 时已做过，避免重复
 * 匹配 + 与 ADR-0006 §9.2 "DelayedEvent 绕过 matcher 直调 runner" 语义一致）。
 *
 * <p>实现方为 {@link SourceDispatcher}，把 (subscription, event) 灌进"扇出 N 个 pipeline"路径，复用
 * runnerPool 反压 + 失败隔离。
 */
@FunctionalInterface
public interface DelayedEventRelayer {

    void relay(Subscription subscription, Event delayedEvent);
}
