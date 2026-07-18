package com.imsw.observe.pipeline.application;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.domain.Pipeline;

/**
 * 单事件分发器（B9 §3.2）。
 *
 * <p>三级反压链：内部有界 {@link BlockingQueue} → N 分发线程（match）→ runnerPool（阻塞提交不丢）。
 *
 * <ul>
 *   <li>{@link #onEvent(Event)} 阻塞入队（{@code queue.put}）：队列满时调用方（MQ onMessage /
 *       Cron fire / Api submit）被反压——例如 MQ 不 ack → 重投/暂停投递。</li>
 *   <li>N 个分发线程循环 {@code queue.take()} → {@link SubscriptionMatcher#match} → 对每个 matched
 *       提交 {@code runnerPool.execute(run)}。</li>
 *   <li>runnerPool 饱和时不丢弃：分发线程在 {@link Semaphore#acquire()} 上阻塞（{@code inFlight}
 *       信号量 cap = runnerPool 工作队列容量 + 最大线程数），保证「入队即被执行」。</li>
 * </ul>
 *
 * <p><b>at-least-once</b>：事件一旦 {@code onEvent} 入队成功，保证最终被 match + 提交执行；
 * runnerPool 饱和靠阻塞反压，不靠拒绝丢弃。下游 pipeline 异步失败不回 MQ（沿用现状）。
 */
public final class SourceDispatcher implements EventListener {

    private static final Logger LOG = LoggerFactory.getLogger(SourceDispatcher.class);

    private final SubscriptionMatcher matcher;

    private final PipelineRunner runner;

    private final ThreadPoolExecutor runnerPool;

    private final DelayedActionHandler delayedActionHandler;

    private final BlockingQueue<Event> queue;

    private final int dispatchThreads;

    /** Cap runnerPool 在途任务（运行中 + 排队），饱和时分发线程阻塞在此而非丢弃事件。 */
    private final Semaphore inFlight;

    private final ThreadFactory threadFactory;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final List<Thread> workers = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 构造分发器。装配后必须调 {@link #start()} 才会启动分发线程；关闭由 {@link #stop()} 驱动。
     *
     * @param queueCapacity 内部队列容量（{@code observe.worker.dispatch-queue-size}）。
     * @param dispatchThreads 分发线程数（{@code observe.worker.dispatch-threads}）。
     * @param runnerInFlight runnerPool 在途上限（工作队列容量 + 最大线程数）。饱和时分发线程阻塞。
     */
    public SourceDispatcher(
            final SubscriptionMatcher matcher,
            final PipelineRunner runner,
            final ThreadPoolExecutor runnerPool,
            final DelayedActionHandler delayedActionHandler,
            final int queueCapacity,
            final int dispatchThreads,
            final int runnerInFlight) {
        this.matcher = matcher;
        this.runner = runner;
        this.runnerPool = runnerPool;
        this.delayedActionHandler = delayedActionHandler;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.dispatchThreads = Math.max(1, dispatchThreads);
        this.inFlight = new Semaphore(Math.max(1, runnerInFlight));
        this.threadFactory = createThreadFactory();
    }

    private static ThreadFactory createThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "source-dispatcher-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 阻塞入队。队列满时调用方被反压（MQ 不 ack / Cron fire 延后 / Api submit 阻塞）。
     *
     * <p>事件一旦 {@code put} 返回即已入队，由分发线程保证 match + 提交执行（at-least-once）。
     */
    @Override
    public void onEvent(final Event event) {
        if (event == null) {
            return;
        }
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted enqueuing event", e);
        }
    }

    /** 启动 N 个分发线程。幂等（重复调用只生效一次）。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < dispatchThreads; i++) {
            Thread t = threadFactory.newThread(this::dispatchLoop);
            workers.add(t);
            t.start();
        }
        LOG.info("SourceDispatcher started: dispatchThreads={}", dispatchThreads);
    }

    /**
     * 停止：先标记停止接受新事件，drain 队列内剩余事件（仍 match + 提交），再中断分发线程。
     *
     * <p>drain 超时 30 秒——超时后强制中断（at-least-once 由 MQ 重投兜底）。
     */
    public void stop() {
        stop(30L);
    }

    /** 包可见：测试可注入 drain 超时（秒）。 */
    void stop(final long drainTimeoutSeconds) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        drainRemaining(drainTimeoutSeconds);
        for (Thread t : workers) {
            t.interrupt();
        }
        for (Thread t : workers) {
            try {
                t.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        LOG.info("SourceDispatcher stopped");
    }

    private void drainRemaining(final long drainTimeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(drainTimeoutSeconds);
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!queue.isEmpty()) {
            LOG.warn(
                    "SourceDispatcher drain timeout: {} events dropped (at-least-once by MQ redelivery)", queue.size());
        }
    }

    /** 分发线程主循环：take → match → 阻塞提交 runnerPool。 */
    private void dispatchLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Event event = queue.poll(100L, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                dispatch(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.warn("dispatch loop error", e);
            }
        }
    }

    private void dispatch(final Event event) throws InterruptedException {
        List<SubscriptionMatcher.MatchedSubscription> matched = matcher.match(event);
        if (matched.isEmpty()) {
            return;
        }
        for (SubscriptionMatcher.MatchedSubscription m : matched) {
            submitMatched(m, event);
        }
    }

    private void submitMatched(final SubscriptionMatcher.MatchedSubscription m, final Event event)
            throws InterruptedException {
        Pipeline pipeline = m.pipeline();
        Long subscriptionId = m.subscription().id();
        if (delayedActionHandler.handle(m.subscription(), event, pipeline)) {
            return;
        }
        // 阻塞提交：runnerPool 饱和时分发线程在此阻塞（acquire），不丢弃事件。
        inFlight.acquire();
        try {
            runnerPool.execute(() -> runAndRelease(pipeline, event, subscriptionId));
        } catch (RuntimeException e) {
            // pool 已关闭等极端情况：释放 permit，避免泄漏（事件可能未执行——at-least-once 由 MQ 重投兜底）。
            inFlight.release();
            LOG.warn("runnerPool.execute rejected event for subscription {}", subscriptionId, e);
        }
    }

    private void runAndRelease(final Pipeline pipeline, final Event event, final Long subscriptionId) {
        try {
            runner.run(pipeline, event, subscriptionId);
        } catch (RuntimeException e) {
            LOG.warn("pipeline {} (subscription {}) execution threw", pipeline.id(), subscriptionId, e);
        } finally {
            inFlight.release();
        }
    }
}
