package com.imsw.observe.pipeline.infrastructure.source;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.event.model.TickEvent;
import com.imsw.observe.kernel.event.model.TickMeta;
import com.imsw.observe.pipeline.application.EventListener;
import com.imsw.observe.pipeline.application.Source;

public final class CronSource implements Source {

    private static final Logger LOG = LoggerFactory.getLogger(CronSource.class);

    private final ScheduledExecutorService scheduler;

    private final String name;

    private final long periodMillis;

    private EventListener listener;

    private ScheduledFuture<?> task;

    public CronSource(final ScheduledExecutorService scheduler, final String name, final long periodMillis) {
        this.scheduler = scheduler;
        this.name = name;
        this.periodMillis = periodMillis;
    }

    @Override
    public SourceType type() {
        return SourceType.CRON;
    }

    @Override
    public void start(final EventListener listener) {
        this.listener = listener;
        task = scheduler.scheduleAtFixedRate(this::tick, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        LOG.info("CronSource '{}' started period_ms={}", name, periodMillis);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel(false);
        }
        LOG.info("CronSource '{}' stopped", name);
    }

    private void tick() {
        if (listener == null) {
            return;
        }
        // B3 占位：保持固定周期调度；TickMeta 为 B4 CronScheduler + cronExpression 预留
        // （此处 cronExpression 传 null，待 B4 用真实表达式填充）。
        TickMeta meta = new TickMeta("cron:" + name, name, null, Map.of());
        Event event = new TickEvent(meta, Instant.now());
        try {
            listener.onBatch(List.of(event));
        } catch (RuntimeException e) {
            LOG.warn("CronSource '{}' tick failed", name, e);
        }
    }
}
