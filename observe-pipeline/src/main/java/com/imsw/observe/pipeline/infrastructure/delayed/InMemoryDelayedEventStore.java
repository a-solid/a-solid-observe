package com.imsw.observe.pipeline.infrastructure.delayed;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.pipeline.application.DelayedEventStore;

/**
 * 延时事件调度实现（infrastructure 层）：纯调度原语（SES + map）。
 *
 * <p>不感知 domain 概念（Subscription/Event/Pipeline）——fire 逻辑（DELAYED 包装 + runner.run）
 * 由 {@link com.imsw.observe.pipeline.application.DelayedActionHandler} 提供。本类只负责：
 * 到点跑 task、Replace 语义（同 key 老 task cancel(false)）、cancel(false) 不中断在跑、
 * fire 后清 map（在 schedule 时包装 fireTask 加 finally 实现——handler 不感知 map）。
 */
public final class InMemoryDelayedEventStore implements DelayedEventStore {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryDelayedEventStore.class);

    private final ScheduledExecutorService scheduler;

    private final ConcurrentMap<String, ScheduledFuture<?>> byCorrelationKey = new ConcurrentHashMap<>();

    public InMemoryDelayedEventStore(final ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void schedule(final String correlationKey, final Runnable fireTask, final Duration delay) {
        if (correlationKey == null) {
            LOG.warn("delayed schedule correlation key null");
            return;
        }
        Runnable wrapped = () -> {
            try {
                fireTask.run();
            } finally {
                byCorrelationKey.remove(correlationKey);
            }
        };
        ScheduledFuture<?> sf = scheduler.schedule(wrapped, delay.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = byCorrelationKey.put(correlationKey, sf);
        if (prev != null) {
            prev.cancel(false);
        }
        LOG.info("delayed scheduled key={} delay_ms={}", correlationKey, delay.toMillis());
    }

    @Override
    public void cancel(final String correlationKey) {
        if (correlationKey == null) {
            LOG.warn("delayed cancel correlation key null");
            return;
        }
        ScheduledFuture<?> prev = byCorrelationKey.remove(correlationKey);
        if (prev != null) {
            prev.cancel(false);
            LOG.info("delayed cancelled key={}", correlationKey);
        }
    }

    @Override
    public int pendingCount() {
        return byCorrelationKey.size();
    }

    @Override
    public void shutdown() {
        scheduler.shutdown();
        byCorrelationKey.clear();
    }
}
