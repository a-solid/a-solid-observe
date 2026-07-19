package com.imsw.observe.pipeline.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

class SourceDispatcherTest {

    private SourceDispatcher dispatcher;

    private ThreadPoolExecutor pool;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.stop();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @Test
    void dispatchesMatchedEventToRunner() throws Exception {
        Pipeline pipeline = pipeline(1L, 1);
        Subscription sub = new Subscription(
                10L,
                "smoke",
                "cdc-orders",
                List.of(1L),
                new Subscription.SourceRef("trade_db", "orders", Set.of(CdcOp.INSERT), SourceType.CDC, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));

        RecordingRunner runner = new RecordingRunner();
        pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        DelayedEventStore delayedStore = new NoopDelayedEventStore();
        DelayedActionHandler delayedHandler = new DelayedActionHandler(delayedStore, () -> dispatcher);
        // queue 容量 8、dispatch 线程 1、runner 在途上限 16（足够大不阻塞测试路径）。
        dispatcher =
                new SourceDispatcher(new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler, 8, 1, 16);

        dispatcher.start();
        dispatcher.onEvent(event("trade_db", "orders", CdcOp.INSERT));
        dispatcher.onEvent(event("trade_db", "payments", CdcOp.INSERT));

        awaitRunnerCount(runner, 1, 2_000L);

        assertThat(runner.received).hasSize(1);
        assertThat(runner.received.get(0).pipelines().get(0).id()).isEqualTo(1L);
    }

    @Test
    void fansOutToMultiplePipelinesWithFailureIsolation() throws Exception {
        // 扇出：一个 subscription 绑 2 个 pipeline；其中一个抛异常不影响另一个执行。
        Pipeline p1 = pipeline(1L, 1);
        Pipeline p2 = pipeline(2L, 1);
        Subscription sub = new Subscription(
                10L,
                "smoke",
                "cdc-orders",
                List.of(1L, 2L),
                new Subscription.SourceRef("trade_db", "orders", Set.of(CdcOp.INSERT), SourceType.CDC, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, p1, 2L, p2), List.of(sub)));

        RecordingRunner runner = new RecordingRunner();
        pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        DelayedActionHandler delayedHandler = new DelayedActionHandler(new NoopDelayedEventStore(), () -> dispatcher);
        dispatcher =
                new SourceDispatcher(new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler, 8, 1, 16);

        dispatcher.start();
        dispatcher.onEvent(event("trade_db", "orders", CdcOp.INSERT));

        awaitRunnerCount(runner, 2, 2_000L);
        // 两个 pipeline 都被 runner 调用（各自一次）。
        assertThat(runner.received).hasSize(2);
    }

    private static void awaitRunnerCount(final RecordingRunner runner, final int expected, final long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (runner.received.size() < expected && System.nanoTime() < deadline) {
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

    private static Event event(final String db, final String table, final CdcOp op) {
        // ADR-0006：CDC 事件现为 CdcEvent + CdcMeta（sourceType 不再挂 meta，由子类型隐式）。
        CdcMeta meta = new CdcMeta("mq", db, table, Map.of());
        return new CdcEvent(meta, Map.of(), Map.of("amount", 1), op, Instant.now());
    }

    /**
     * 订阅级 action 分发（delayed-redesign spec D2/D6）：SCHEDULE 订阅命中后 dispatcher 在订阅层调
     * store.schedule 一次（不在内层 pipeline for 内 N 次调用）。
     */
    @Test
    void scheduleActionDispatchedOnceAtSubscriptionLevel() throws Exception {
        Pipeline p1 = pipeline(1L, 1);
        Pipeline p2 = pipeline(2L, 1);
        Subscription sub = new Subscription(
                10L,
                "smoke",
                "cdc-orders",
                List.of(1L, 2L),
                new Subscription.SourceRef("trade_db", "orders", Set.of(CdcOp.INSERT), SourceType.CDC, null, null),
                null,
                new Action.Schedule(Duration.ofMinutes(30), "after.orderId"));
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, p1, 2L, p2), List.of(sub)));

        RecordingRunner runner = new RecordingRunner();
        pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        RecordingDelayedEventStore store = new RecordingDelayedEventStore();
        DelayedActionHandler delayedHandler = new DelayedActionHandler(store, () -> dispatcher);
        dispatcher =
                new SourceDispatcher(new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler, 8, 1, 16);

        dispatcher.start();
        // CDC INSERT 携带 orderId=order-123，path "after.orderId" 抽出 key="order-123"。
        CdcMeta meta = new CdcMeta("mq", "trade_db", "orders", Map.of());
        dispatcher.onEvent(
                new CdcEvent(meta, Map.of(), Map.of("amount", 1, "orderId", "order-123"), CdcOp.INSERT, Instant.now()));

        awaitScheduleCount(store, 1, 2_000L);
        // SCHEDULE 在订阅层调一次 store.schedule（不为每个 pipeline 各调一次）。
        assertThat(store.scheduled).hasSize(1);
        assertThat(store.scheduled.get(0).key()).isEqualTo("smoke:order-123");
        assertThat(store.scheduled.get(0).delay()).isEqualTo(Duration.ofMinutes(30));
        // RUN 路径不应触发（SCHEDULE 已消费）。
        assertThat(runner.received).isEmpty();
    }

    /**
     * 订阅级 action：CANCEL 命中后 dispatcher 在订阅层调 store.cancel 一次，按 namespace 级 key。
     */
    @Test
    void cancelActionDispatchedOnceAtSubscriptionLevelWithNamespaceKey() throws Exception {
        Subscription sub = new Subscription(
                11L,
                "billing",
                "cdc-orders",
                List.of(1L),
                new Subscription.SourceRef("trade_db", "orders", Set.of(CdcOp.UPDATE), SourceType.CDC, null, null),
                null,
                new Action.Cancel("after.orderId"));
        PipelineRegistry registry = new PipelineRegistry();
        Pipeline pipeline = pipeline(1L, 1);
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));

        RecordingRunner runner = new RecordingRunner();
        pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        RecordingDelayedEventStore store = new RecordingDelayedEventStore();
        DelayedActionHandler delayedHandler = new DelayedActionHandler(store, () -> dispatcher);
        dispatcher =
                new SourceDispatcher(new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler, 8, 1, 16);

        dispatcher.start();
        CdcMeta meta = new CdcMeta("mq", "trade_db", "orders", Map.of());
        dispatcher.onEvent(new CdcEvent(meta, Map.of(), Map.of("orderId", "order-456"), CdcOp.UPDATE, Instant.now()));

        awaitCancelCount(store, 1, 2_000L);
        assertThat(store.cancelled).containsExactly("billing:order-456");
        // RUN 不触发。
        assertThat(runner.received).isEmpty();
    }

    /**
     * Delayed replay 路径（fire → onEvent → matcher 按 subscriptionId 路由 → 扇出 N pipeline）：
     * 模拟 handler fire 触发，DelayedEvent 作为普通事件经 dispatcher → matcher 路由回原订阅扇出。
     */
    @Test
    void delayedReplayRoutedByMatcherViaSubscriptionId() throws Exception {
        Pipeline p1 = pipeline(1L, 1);
        Pipeline p2 = pipeline(2L, 1);
        Subscription sub = new Subscription(
                12L,
                "smoke",
                "cdc-orders",
                List.of(1L, 2L),
                new Subscription.SourceRef("trade_db", "orders", Set.of(CdcOp.INSERT), SourceType.CDC, null, null),
                null,
                new Action.Schedule(Duration.ofMillis(50), "after.orderId"));
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, p1, 2L, p2), List.of(sub)));

        RecordingRunner runner = new RecordingRunner();
        pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        // 用真实的 InMemoryDelayedEventStore：短 delay（50ms）后真实 fire，验证 replay 路径端到端跑通。
        java.util.concurrent.ScheduledExecutorService ses =
                java.util.concurrent.Executors.newScheduledThreadPool(1, r -> {
                    Thread t = new Thread(r, "delayed-test");
                    t.setDaemon(true);
                    return t;
                });
        try {
            com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore store =
                    new com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore(ses);
            DelayedActionHandler delayedHandler = new DelayedActionHandler(store, () -> dispatcher);
            dispatcher = new SourceDispatcher(
                    new DefaultSubscriptionMatcher(registry), runner, pool, delayedHandler, 8, 1, 16);

            dispatcher.start();
            CdcMeta meta = new CdcMeta("mq", "trade_db", "orders", Map.of());
            dispatcher.onEvent(
                    new CdcEvent(meta, Map.of(), Map.of("orderId", "order-789"), CdcOp.INSERT, Instant.now()));

            // 50ms delay + 调度开销；等两个 pipeline 都被 runner 调用（matcher 路由后扇出）。
            awaitRunnerCount(runner, 2, 2_000L);
            assertThat(runner.received).hasSize(2);
            // 调度阶段（首次 onEvent 后）runner 还不应被触发——SCHEDULE 已消费。
            // 这里只在 fire 后断言计数，初值不验证（avoid flake）。
        } finally {
            ses.shutdownNow();
        }
    }

    private static void awaitScheduleCount(
            final RecordingDelayedEventStore store, final int expected, final long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (store.scheduled.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
    }

    private static void awaitCancelCount(
            final RecordingDelayedEventStore store, final int expected, final long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (store.cancelled.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
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
                            "test",
                            List.of(pipeline.id()),
                            null,
                            null,
                            new Action.Run()),
                    List.of(pipeline)));
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

    /** 记录 schedule/cancel 调用的端口实现——用于验证 dispatcher 是否在订阅级调一次。 */
    static final class RecordingDelayedEventStore implements DelayedEventStore {

        final CopyOnWriteArrayList<ScheduleCall> scheduled = new CopyOnWriteArrayList<>();

        final CopyOnWriteArrayList<String> cancelled = new CopyOnWriteArrayList<>();

        @Override
        public void schedule(final String correlationKey, final Runnable fireTask, final Duration delay) {
            scheduled.add(new ScheduleCall(correlationKey, fireTask, delay));
        }

        @Override
        public void cancel(final String correlationKey) {
            cancelled.add(correlationKey);
        }

        @Override
        public int pendingCount() {
            return scheduled.size();
        }

        @Override
        public void shutdown() {
            // no-op
        }

        record ScheduleCall(String key, Runnable fireTask, Duration delay) {}
    }
}
