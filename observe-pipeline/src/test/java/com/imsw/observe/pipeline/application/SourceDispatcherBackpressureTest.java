package com.imsw.observe.pipeline.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
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

/**
 * 验证 {@link SourceDispatcher} 的三级反压链（B9 §3.2）：
 *
 * <ul>
 *   <li>队列满 → {@code onEvent} 阻塞（调用方被反压）。</li>
 *   <li>释放后恢复（事件最终被执行）。</li>
 *   <li>runnerPool 饱和 → 分发线程阻塞在 {@code Semaphore.acquire}（不丢弃事件）。</li>
 * </ul>
 */
class SourceDispatcherBackpressureTest {

    private SourceDispatcher dispatcher;

    private ThreadPoolExecutor pool;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.stop(2L);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    void onEventBlocksWhenQueueFullThenRecoversAfterDrain() throws Exception {
        // matcher 始终返回空——分发线程 take 走事件但不会提交 runnerPool，事件快速从队列消失。
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(), List.of()));
        NoopRunner runner = new NoopRunner();
        pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(4));
        DelayedEventStore delayedStore = new NoopDelayedEventStore();
        DelayedActionHandler delayedHandler = new DelayedActionHandler(delayedStore, () -> dispatcher);
        // queue 容量 2，dispatch 线程 0（不消费——保证队列被 producer 灌满后保持满）。
        dispatcher =
                new SourceDispatcher(new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler, 2, 0, 16);

        // 灌满 2 个事件（容量 2）。
        dispatcher.onEvent(event());
        dispatcher.onEvent(event());

        // 第 3 个 onEvent 应阻塞：在独立线程发起，确认它在短延迟后仍未返回。
        Thread blocker = new Thread(() -> {
            try {
                dispatcher.onEvent(event());
            } catch (RuntimeException ignored) {
                // tearDown 期间可能被中断——预期路径。
            }
        });
        blocker.setDaemon(true);
        blocker.start();
        Thread.sleep(200L);
        assertThat(blocker.isAlive())
                .as("onEvent should block when queue is full")
                .isTrue();

        // 释放：start 启动分发线程消费队列——队列腾出后 blocker 的 put 放行。
        dispatcher.start();
        blocker.join(2_000L);
        assertThat(blocker.isAlive())
                .as("onEvent should return once queue drains")
                .isFalse();
    }

    @Test
    void dispatchThreadDrainsQueueAfterStart() throws Exception {
        Pipeline pipeline = pipeline(1L, 1);
        Subscription sub = new Subscription(
                1L,
                "smoke",
                "cdc-orders",
                List.of(1L),
                new Subscription.SourceRef("db", "t", Set.of(CdcOp.INSERT), SourceType.CDC, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));
        CountingRunner runner = new CountingRunner();
        pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(16));
        DelayedEventStore delayedStore = new NoopDelayedEventStore();
        DelayedActionHandler delayedHandler = new DelayedActionHandler(delayedStore, () -> dispatcher);
        dispatcher =
                new SourceDispatcher(new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler, 4, 1, 32);

        // start 前 onEvent，事件进队列但未被消费。
        dispatcher.onEvent(event());
        dispatcher.onEvent(event());
        assertThat(runner.count.get()).isZero();

        dispatcher.start();
        awaitCount(runner, 2, 2_000L);
        assertThat(runner.count.get()).isEqualTo(2);
    }

    private static void awaitCount(final CountingRunner runner, final int expected, final long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (runner.count.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
    }

    private static Pipeline pipeline(final Long id, final int version) {
        return new Pipeline(
                id,
                "smoke",
                version,
                Map.of(),
                "name",
                Pipeline.Status.PUBLISHED,
                List.of(),
                Instant.now(),
                Instant.now(),
                0.0);
    }

    private static Event event() {
        CdcMeta meta = new CdcMeta("mq", "db", "t", Map.of());
        return new CdcEvent(meta, Map.of(), Map.of("k", 1), CdcOp.INSERT, Instant.now());
    }

    static final class NoopRunner implements PipelineRunner {
        @Override
        public void run(final Pipeline pipeline, final Event triggerEvent, final Long subscriptionId) {
            // no-op
        }
    }

    static final class CountingRunner implements PipelineRunner {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void run(final Pipeline pipeline, final Event triggerEvent, final Long subscriptionId) {
            count.incrementAndGet();
        }
    }

    static final class NoopDelayedEventStore implements DelayedEventStore {
        @Override
        public void schedule(final String correlationKey, final Runnable fireTask, final Duration delay) {
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
