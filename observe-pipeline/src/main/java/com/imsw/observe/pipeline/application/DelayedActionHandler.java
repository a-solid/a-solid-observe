package com.imsw.observe.pipeline.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
 * <p>承接 domain 逻辑：correlationKey 提取（{@link EventPaths}）、namespace 级 key 拼装、
 * SCHEDULE/CANCEL 路由、DELAYED 包装 event 构造、fire → {@link DelayedEventRelayer} 重放。依赖
 * {@link DelayedEventStore} 端口（非具体实现），实现可换为 Redis/DB 持久化而 handler 不变（ADR-0001 依赖倒置）。
 *
 * <p><b>订阅级 vs 旧 (sub,pipeline) 上下文</b>：扇出后一个订阅绑 N 个 pipeline，schedule 是订阅级资源
 * （一个延迟任务，到期 fire 时复用 matcher 扇出），不能按 (sub,pipeline) 各 schedule 一次。dispatcher
 * 在外层 for 内调本类一次（per 订阅），不在内层 pipeline for 内调用。
 *
 * <p><b>correlationKey 命名空间</b>：{@code fullKey = namespace + ":" + rawKey}（D4）。同 namespace 内的
 * schedule/cancel 自然配对，cancel 可从任意订阅（CDC/CRON/API）发起；跨 namespace 不能 cancel（软隔离）。
 */
public final class DelayedActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DelayedActionHandler.class);

    private static final String NS_SEPARATOR = ":";

    private final DelayedEventStore store;

    private volatile DelayedEventRelayer relayer;

    public DelayedActionHandler(final DelayedEventStore store, final DelayedEventRelayer relayer) {
        this.store = store;
        this.relayer = relayer;
    }

    /**
     * Wiring 入口：dispatcher bean 创建后调用，把自己作为 relayer 注入——避免构造期循环依赖
     * （dispatcher 持有 handler、handler 持有 relayer=dispatcher）。
     */
    public void setRelayer(final DelayedEventRelayer relayer) {
        this.relayer = relayer;
    }

    /**
     * 订阅级 action 分发。返回 true 表示已消费（SCHEDULE/CANCEL），dispatcher 不再走 RUN 扇出；
     * 返回 false 表示非延时 action（RUN），dispatcher 走扇出路径。
     */
    public boolean handle(final Subscription subscription, final Event event) {
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
            Instant scheduledAt = Instant.now();
            store.schedule(fullKey, () -> fire(subscription, event, rawKey, fullKey, scheduledAt), schedule.delay());
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
            final Subscription subscription,
            final Event original,
            final String correlationKey,
            final String fullKey,
            final Instant scheduledAt) {
        try {
            Event delayed = wrapAsDelayed(original, subscription, correlationKey, scheduledAt);
            // 重放：DelayedEvent 经 relayer 投回 dispatcher → matcher 重新匹配订阅 → 扇出 N 个 pipeline
            // （与 ADR-0006 §9.2 "延时到点重放绕过 source 路由、复用 matcher 扇出" 一致）。
            relayer.relay(subscription, delayed);
        } catch (RuntimeException e) {
            LOG.warn("delayed fire failed subscription={} key={}", subscription.id(), fullKey, e);
        }
    }

    private static DelayedEvent wrapAsDelayed(
            final Event original,
            final Subscription subscription,
            final String correlationKey,
            final Instant scheduledAt) {
        DelayedMeta meta = new DelayedMeta(
                "delayed:" + subscription.id(),
                Map.of(
                        "schedule_id", UUID.randomUUID().toString(),
                        "subscription_id", subscription.id(),
                        "scheduled_at", scheduledAt.toString(),
                        "fired_at", Instant.now().toString(),
                        "correlation_key", correlationKey));
        return new DelayedEvent(meta, original, Instant.now());
    }

    private static String namespacedKey(final String namespace, final String rawKey) {
        return namespace + NS_SEPARATOR + rawKey;
    }

    private static String extractKey(final Event event, final String path) {
        Object value = EventPaths.get(event, path);
        return value == null ? null : value.toString();
    }
}
