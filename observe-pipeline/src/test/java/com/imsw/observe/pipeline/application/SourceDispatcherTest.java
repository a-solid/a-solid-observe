package com.imsw.observe.pipeline.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.CdcMeta;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Subscription;
import com.imsw.observe.pipeline.infrastructure.subscription.DefaultSubscriptionMatcher;

class SourceDispatcherTest {

    @Test
    void dispatchesMatchedEventToRunner() throws Exception {
        Pipeline pipeline = pipeline(1L, 1);
        Subscription sub = new Subscription(
                10L,
                "smoke",
                1L,
                1,
                new Subscription.SourceRef("mq", "topic", "trade_db", "orders", Set.of(CdcOp.INSERT), SourceType.CDC),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));

        RecordingRunner runner = new RecordingRunner();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        com.imsw.observe.pipeline.application.DelayedEventStore delayedStore = new NoopDelayedEventStore();
        DelayedActionHandler delayedHandler = new DelayedActionHandler(delayedStore, runner);
        SourceDispatcher dispatcher =
                new SourceDispatcher(new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler);

        dispatcher.onBatch(List.of(event("trade_db", "orders", CdcOp.INSERT)));
        dispatcher.onBatch(List.of(event("trade_db", "payments", CdcOp.INSERT)));

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        assertThat(runner.received).hasSize(1);
        assertThat(runner.received.get(0).pipeline().id()).isEqualTo(1L);
    }

    private static Pipeline pipeline(final Long id, final int version) {
        return new Pipeline(
                id,
                "smoke",
                version,
                "team",
                "app",
                Map.of(),
                "name",
                Pipeline.Status.PUBLISHED,
                List.of(),
                Instant.now(),
                Instant.now(),
                0.0);
    }

    private static Event event(final String db, final String table, final CdcOp op) {
        // ADR-0006：CDC 事件现为 CdcEvent + CdcMeta（sourceType 不再挂 meta，由子类型隐式）。
        CdcMeta meta = new CdcMeta("mq", db, table, Map.of());
        return new CdcEvent(meta, Map.of(), Map.of("amount", 1), op, Instant.now());
    }

    static final class RecordingRunner implements PipelineRunner {

        final List<SubscriptionMatcher.MatchedSubscription> received =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void run(final Pipeline pipeline, final Event triggerEvent, final Long subscriptionId) {
            received.add(new SubscriptionMatcher.MatchedSubscription(
                    new Subscription(
                            subscriptionId,
                            pipeline.namespace(),
                            pipeline.id(),
                            pipeline.version(),
                            null,
                            null,
                            new Action.Run()),
                    pipeline));
        }
    }

    /** No-op 端口实现：SourceDispatcherTest 只走 Run action，不会触发 schedule/cancel。 */
    static final class NoopDelayedEventStore implements DelayedEventStore {

        @Override
        public void schedule(final String correlationKey, final Runnable fireTask, final java.time.Duration delay) {
            // no-op
        }

        @Override
        public void cancel(final String correlationKey) {
            // no-op
        }

        @Override
        public int pendingCount() {
            return 0;
        }

        @Override
        public void shutdown() {
            // no-op
        }
    }
}
