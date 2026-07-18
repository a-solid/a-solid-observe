package com.imsw.observe.pipeline.infrastructure.delayed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

class InMemoryDelayedEventStoreTest {

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void scheduleFiresRunnerAfterDelay() throws Exception {
        CountingRunner runner = new CountingRunner();
        InMemoryDelayedEventStore store = new InMemoryDelayedEventStore(scheduler, runner);
        Subscription sub = sub(10L, new Action.Schedule(Duration.ofMillis(50), "after.trade_id", 1L));
        store.schedule(sub, event("t1"), pipeline(), Duration.ofMillis(50), "after.trade_id");

        assertThat(runner.awaitRuns(1)).isTrue();
        assertThat(store.pendingCount()).isZero();
        store.shutdown();
    }

    @Test
    void scheduleReplacesSameKey() throws Exception {
        CountingRunner runner = new CountingRunner();
        InMemoryDelayedEventStore store = new InMemoryDelayedEventStore(scheduler, runner);
        Subscription sub = sub(10L, new Action.Schedule(Duration.ofMillis(200), "after.trade_id", 1L));
        store.schedule(sub, event("t1"), pipeline(), Duration.ofMillis(200), "after.trade_id");
        store.schedule(sub, event("t1"), pipeline(), Duration.ofMillis(200), "after.trade_id");

        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(runner.awaitRuns(1)).isTrue();
        assertThat(runner.runs.get()).isLessThanOrEqualTo(1);
        store.shutdown();
    }

    @Test
    void cancelRemovesPending() {
        CountingRunner runner = new CountingRunner();
        InMemoryDelayedEventStore store = new InMemoryDelayedEventStore(scheduler, runner);
        Subscription sub = sub(10L, new Action.Cancel("after.trade_id"));
        store.schedule(sub, event("t1"), pipeline(), Duration.ofSeconds(5), "after.trade_id");
        assertThat(store.pendingCount()).isEqualTo(1);

        store.cancel(sub, event("t1"), "after.trade_id");
        assertThat(store.pendingCount()).isZero();
        store.shutdown();
    }

    private static Subscription sub(final Long id, final Action action) {
        return new Subscription(
                id,
                1L,
                1,
                new Subscription.SourceRef(null, null, "db", "tbl", java.util.Set.of(), SourceType.CDC),
                null,
                action);
    }

    private static Event event(final String tradeId) {
        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "mq", "db", "tbl", Map.of());
        return new Event(meta, Map.of(), Map.of("trade_id", tradeId), Op.UPDATE, Instant.now());
    }

    private static Pipeline pipeline() {
        return new Pipeline(
                1L,
                1,
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

    private static final class CountingRunner implements PipelineRunner {

        final AtomicInteger runs = new AtomicInteger();

        @Override
        public void run(final Pipeline pipeline, final Event triggerEvent, final Long subscriptionId) {
            runs.incrementAndGet();
        }

        boolean awaitRuns(final int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                if (runs.get() >= expected) {
                    return true;
                }
                Thread.sleep(20);
            }
            return runs.get() >= expected;
        }
    }
}
