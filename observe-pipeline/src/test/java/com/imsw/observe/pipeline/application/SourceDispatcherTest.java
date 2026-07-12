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

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Subscription;
import com.imsw.observe.pipeline.infrastructure.subscription.DefaultSubscriptionMatcher;

class SourceDispatcherTest {

    @Test
    void dispatchesMatchedEventToRunner() throws Exception {
        Pipeline pipeline = pipeline("p1", 1);
        Subscription sub = new Subscription(
                "s1",
                "p1",
                1,
                new Subscription.SourceRef("mq", "topic", "trade_db", "orders", Set.of(Op.INSERT), SourceType.CDC),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of("p1", pipeline), List.of(sub)));

        RecordingRunner runner = new RecordingRunner();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore delayedStore =
                new com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore(null, runner);
        DelayedActionHandler delayedHandler = new DelayedActionHandler(delayedStore);
        SourceDispatcher dispatcher =
                new SourceDispatcher(new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler);

        dispatcher.onBatch(List.of(event("trade_db", "orders", Op.INSERT)));
        dispatcher.onBatch(List.of(event("trade_db", "payments", Op.INSERT)));

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        assertThat(runner.received).hasSize(1);
        assertThat(runner.received.get(0).pipeline().id()).isEqualTo("p1");
    }

    private static Pipeline pipeline(final String id, final int version) {
        return new Pipeline(
                id,
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

    private static Event event(final String db, final String table, final Op op) {
        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "mq", db, table, Map.of());
        return new Event(meta, Map.of(), Map.of("amount", 1), op, Instant.now());
    }

    static final class RecordingRunner implements PipelineRunner {

        final List<SubscriptionMatcher.MatchedSubscription> received =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void run(final Pipeline pipeline, final Event triggerEvent, final String subscriptionId) {
            received.add(new SubscriptionMatcher.MatchedSubscription(
                    new Subscription(subscriptionId, pipeline.id(), pipeline.version(), null, null, new Action.Run()),
                    pipeline));
        }
    }
}
