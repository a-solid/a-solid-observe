package com.imsw.observe.pipeline.application;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.DelayedEvent;
import com.imsw.observe.kernel.event.model.DelayedMeta;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.EventPaths;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

/**
 * 延时动作处理器（application 层）——订阅级语义（delayed-redesign spec D2/D6/D7）。
 *
 * <p>承接 domain 逻辑：correlationKey 提取（{@link EventPaths}）、namespace 级 key 拼装、SCHEDULE/CANCEL 路由、
 * DelayedEvent 包装构造、fire → {@link EventListener#onEvent} 回流。依赖 {@link DelayedEventStore} 端口
 * （非具体实现），实现可换为 Redis/DB 持久化而 handler 不变（ADR-0001 依赖倒置）。
 *
 * <p><b>订阅级 vs 旧 (sub,pipeline) 上下文</b>：扇出后一个订阅绑 N 个 pipeline，schedule 是订阅级资源
 * （一个延迟任务，到期 fire 时复用 matcher 扇出），不能按 (sub,pipeline) 各 schedule 一次。dispatcher
 * 在外层 for 内调本类一次（per 订阅），不在内层 pipeline for 内调用。
 *
 * <p><b>fire 回流路径</b>：fire 到点构造 {@link DelayedEvent} 后调 {@code dispatcher.onEvent(delayed)}——
 * DelayedEvent 作为普通事件经 dispatcher 队列、由 matcher 按 {@link DelayedMeta#subscriptionId()} 路由回原订阅、
 * 扇出 N 个 pipeline（旧"绕过 matcher 直调 PipelineRunner"语义已被取代，见 ADR-0006 addendum）。
 *
 * <p><b>correlationKey 命名空间</b>：{@code fullKey = namespace + ":" + rawKey}（D4）。同 namespace 内的
 * schedule/cancel 自然配对，cancel 可从任意订阅（CDC/CRON/API）发起；跨 namespace 不能 cancel（软隔离）。
 */
public final class DelayedActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DelayedActionHandler.class);

    private static final String NS_SEPARATOR = ":";

    private final DelayedEventStore store;

    /**
     * Dispatcher 延迟获取——构造期 dispatcher bean 尚未就绪（循环：dispatcher 持有 handler、handler 持有
     * dispatcher=EventListener），用 {@code Supplier} 让 fire 时才解析。application 层纯 Java（无 Spring 注解），
     * 装配方传 {@code () -> dispatcher}，Spring 自然解析循环。
     */
    private final Supplier<EventListener> dispatcherSupplier;

    public DelayedActionHandler(final DelayedEventStore store, final Supplier<EventListener> dispatcherSupplier) {
        this.store = store;
        this.dispatcherSupplier = dispatcherSupplier;
    }

    /**
     * 订阅级 action 分发。返回 true 表示已消费（SCHEDULE/CANCEL），dispatcher 不再走 RUN 扇出；
     * 返回 false 表示非延时 action（RUN），dispatcher 走扇出路径。
     *
     * <p>DelayedEvent 早返：DelayedEvent 是 fire 产物，回流到 handle 表示已到点——必须走 RUN 扇出
     * （return false），不能再 schedule/cancel 自己，避免无限自我重排。
     */
    public boolean handle(final Subscription subscription, final Event event) {
        if (event instanceof DelayedEvent) {
            return false;
        }
        Action action = subscription.action();
        if (action instanceof Action.Schedule schedule) {
            String rawKey = extractKey(event, schedule.correlationKeyPath());
            if (rawKey == null) {
                LOG.warn(
                        "schedule correlationKey null, skipping schedule subscription={} path={}",
                        subscription.id(),
                        schedule.correlationKeyPath());
                return true;
            }
            String fullKey = namespacedKey(subscription.namespace(), rawKey);
            store.schedule(fullKey, () -> fire(subscription, event, rawKey, fullKey), schedule.delay());
            return true;
        }
        if (action instanceof Action.Cancel cancel) {
            String rawKey = extractKey(event, cancel.correlationKeyPath());
            if (rawKey == null) {
                // cancel 在 keyPath 抽不到值时通常意味着事件无对应字段——直接 cancel("") 会误删同 namespace 下
                // 空字符串 key 的 schedule（病态但理论存在），故显式 no-op + warn。
                LOG.warn(
                        "cancel correlationKey null, skipping cancel subscription={} path={}",
                        subscription.id(),
                        cancel.correlationKeyPath());
                return true;
            }
            store.cancel(namespacedKey(subscription.namespace(), rawKey));
            return true;
        }
        return false;
    }

    private void fire(
            final Subscription subscription, final Event original, final String correlationKey, final String fullKey) {
        try {
            Event delayed = wrapAsDelayed(original, subscription, correlationKey);
            // DelayedEvent 作为普通事件回流：dispatcher.onEvent → matcher 按 subscriptionId 路由回原订阅 →
            // 扇出 N 个 pipeline（与 ADR-0006 addendum "DelayedEvent 走 matcher" 一致）。
            dispatcherSupplier.get().onEvent(delayed);
        } catch (RuntimeException e) {
            LOG.warn("delayed fire failed subscription={} key={}", subscription.id(), fullKey, e);
        }
    }

    private static DelayedEvent wrapAsDelayed(
            final Event original, final Subscription subscription, final String correlationKey) {
        DelayedMeta meta = new DelayedMeta(subscription.id(), correlationKey);
        return new DelayedEvent(meta, original, java.time.Instant.now());
    }

    private static String namespacedKey(final String namespace, final String rawKey) {
        return namespace + NS_SEPARATOR + rawKey;
    }

    private static String extractKey(final Event event, final String path) {
        Object value = EventPaths.get(event, path);
        return value == null ? null : value.toString();
    }
}
