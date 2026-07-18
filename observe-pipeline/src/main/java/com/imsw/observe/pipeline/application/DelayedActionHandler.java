package com.imsw.observe.pipeline.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.EventPaths;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

/**
 * 延时动作处理器（application 层）。承接 domain 逻辑：correlationKey 提取（EventPaths）、
 * DELAYED 包装 event 构造、fire→runner.run。依赖 {@link DelayedEventStore} 端口（非具体实现），
 * 实现可换为 Redis/DB 持久化而 handler 不变（ADR-0001 依赖倒置）。
 */
public final class DelayedActionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DelayedActionHandler.class);

    private final DelayedEventStore store;

    private final PipelineRunner runner;

    public DelayedActionHandler(final DelayedEventStore store, final PipelineRunner runner) {
        this.store = store;
        this.runner = runner;
    }

    public boolean handle(final Subscription subscription, final Event event, final Pipeline pipeline) {
        Action action = subscription.action();
        if (action instanceof Action.Schedule schedule) {
            String key = extractKey(event, schedule.correlationKeyPath());
            if (key == null) {
                return true; // 无法调度，视为已处理（避免 fall-through 到 RUN）
            }
            Instant scheduledAt = Instant.now();
            store.schedule(key, () -> fire(subscription, event, pipeline, key, scheduledAt), schedule.delay());
            return true;
        }
        if (action instanceof Action.Cancel cancel) {
            String key = extractKey(event, cancel.correlationKeyPath());
            store.cancel(key); // cancel 内部处理 null key（no-op + warn）
            return true;
        }
        return false;
    }

    private void fire(
            final Subscription subscription,
            final Event original,
            final Pipeline pipeline,
            final String correlationKey,
            final Instant scheduledAt) {
        try {
            Event delayed = wrapAsDelayed(original, subscription, correlationKey, scheduledAt);
            runner.run(pipeline, delayed, null);
        } catch (RuntimeException e) {
            LOG.warn("delayed fire failed subscription={} key={}", subscription.id(), correlationKey, e);
        }
    }

    private static Event wrapAsDelayed(
            final Event original,
            final Subscription subscription,
            final String correlationKey,
            final Instant scheduledAt) {
        Event.EventMeta originalMeta = original.meta();
        Event.EventMeta meta = new Event.EventMeta(
                SourceType.CDC,
                "delayed:" + subscription.id(),
                originalMeta == null ? null : originalMeta.db(),
                originalMeta == null ? null : originalMeta.table(),
                Map.of(
                        "schedule_id", UUID.randomUUID().toString(),
                        "subscription_id", subscription.id(),
                        "original_event", original,
                        "scheduled_at", scheduledAt.toString(),
                        "fired_at", Instant.now().toString(),
                        "correlation_key", correlationKey));
        return new Event(meta, null, null, Op.DELAYED, Instant.now());
    }

    private static String extractKey(final Event event, final String path) {
        Object value = EventPaths.get(event, path);
        return value == null ? null : value.toString();
    }
}
