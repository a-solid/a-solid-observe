package com.imsw.observe.pipeline.infrastructure.delayed;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Subscription;
import com.imsw.observe.pipeline.infrastructure.source.EventPaths;

public final class InMemoryDelayedEventStore {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryDelayedEventStore.class);

    private final ScheduledExecutorService scheduler;

    private final PipelineRunner runner;

    private final ConcurrentMap<String, ScheduledFuture<?>> byCorrelationKey = new ConcurrentHashMap<>();

    public InMemoryDelayedEventStore(final ScheduledExecutorService scheduler, final PipelineRunner runner) {
        this.scheduler = scheduler;
        this.runner = runner;
    }

    public void schedule(
            final Subscription subscription,
            final Event originalEvent,
            final Pipeline pipeline,
            final Duration delay,
            final String correlationKeyPath) {
        String key = extractKey(originalEvent, correlationKeyPath);
        if (key == null) {
            LOG.warn("delayed schedule correlation key null for subscription {}", subscription.id());
            return;
        }
        Instant scheduledAt = Instant.now();
        ScheduledFuture<?> sf = scheduler.schedule(
                () -> fire(subscription, originalEvent, pipeline, key, scheduledAt),
                delay.toMillis(),
                TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = byCorrelationKey.put(key, sf);
        if (prev != null) {
            prev.cancel(false);
        }
        LOG.info("delayed scheduled subscription={} key={} delay_ms={}", subscription.id(), key, delay.toMillis());
    }

    public void cancel(final Subscription subscription, final Event triggerEvent, final String correlationKeyPath) {
        String key = extractKey(triggerEvent, correlationKeyPath);
        if (key == null) {
            LOG.warn("delayed cancel correlation key null for subscription {}", subscription.id());
            return;
        }
        ScheduledFuture<?> prev = byCorrelationKey.remove(key);
        if (prev != null) {
            prev.cancel(false);
            LOG.info("delayed cancelled subscription={} key={}", subscription.id(), key);
        }
    }

    public int pendingCount() {
        return byCorrelationKey.size();
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
        } finally {
            byCorrelationKey.remove(correlationKey);
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

    public void shutdown() {
        scheduler.shutdown();
        byCorrelationKey.clear();
    }
}
