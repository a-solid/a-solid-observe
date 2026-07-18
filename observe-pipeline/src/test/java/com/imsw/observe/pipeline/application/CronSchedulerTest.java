package com.imsw.observe.pipeline.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.event.model.TickEvent;
import com.imsw.observe.pipeline.application.PipelineRegistry.Snapshot;
import com.imsw.observe.pipeline.domain.subscription.Subscription;
import com.imsw.observe.pipeline.domain.subscription.Subscription.SourceRef.Concurrent;

/**
 * {@link CronScheduler} 单测。timing 尽量确定性化：
 * <ul>
 *   <li>schedule / cancel / diff：用 {@code trackedCount()} 立即断言，不等真实 fire。</li>
 *   <li>TickEvent 投递：{@code "* * * * * ?"} 每秒 + {@link CountDownLatch} await（≤3s）。</li>
 *   <li>SKIP 并发：第一次 fire 真实进入慢 listener 并 park 在 latch 上；测试线程在 park 期间
 *       直接再调一次 {@code fire(id, sub)}（包可见），断言第二次被 SKIP（listener 计数不增）。</li>
 *   <li>misfire：sync 后 {@code CronExpression.next(now)} 给出的下一发严格在未来——断言没有
 *       立即触发（即无 catch-up）。</li>
 * </ul>
 */
class CronSchedulerTest {

    private ScheduledExecutorService ses;

    private CapturingListener listener;

    private CronScheduler scheduler;

    @BeforeEach
    void setUp() {
        ses = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "cron-sched-test");
            t.setDaemon(true);
            return t;
        });
        listener = new CapturingListener();
        scheduler = new CronScheduler(ses, listener);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void syncSchedulesCronSubscription() {
        Subscription sub = cronSub(1L, "every-second", "* * * * * ?", Concurrent.SKIP);
        scheduler.sync(snapshot(sub));

        assertThat(scheduler.trackedCount()).isEqualTo(1);
    }

    @Test
    void syncIsIdempotentForSameSnapshot() {
        Subscription sub = cronSub(1L, "every-second", "* * * * * ?", Concurrent.SKIP);
        Snapshot snap = snapshot(sub);

        scheduler.sync(snap);
        scheduler.sync(snap);
        scheduler.sync(snap);

        assertThat(scheduler.trackedCount()).isEqualTo(1);
    }

    @Test
    void syncReschedulesWhenExpressionChanges() throws Exception {
        // 第一发用 1 秒表达式触发一次（验证旧调度真的活过），然后改表达式。
        Subscription sub = cronSub(1L, "every-second", "* * * * * ?", Concurrent.SKIP);
        scheduler.sync(snapshot(sub));
        listener.firstEventLatch.await(3, TimeUnit.SECONDS);
        int firesBefore = listener.tickCount.get();

        // 变更表达式 → 旧句柄 cancel、新句柄起（tracked 仍 1）。
        Subscription changed = cronSub(1L, "every-second", "*/2 * * * * ?", Concurrent.SKIP);
        scheduler.sync(snapshot(changed));
        assertThat(scheduler.trackedCount()).isEqualTo(1);

        // 等待新一轮触发，确认新调度链活着（且只增加、不串味）。
        listener.firstEventLatch2.await(3, TimeUnit.SECONDS);
        int firesAfter = listener.tickCount.get();
        assertThat(firesAfter).isGreaterThan(firesBefore);
    }

    @Test
    void syncCancelsRemovedSubscription() {
        Subscription sub = cronSub(1L, "every-second", "* * * * * ?", Concurrent.SKIP);
        scheduler.sync(snapshot(sub));
        assertThat(scheduler.trackedCount()).isEqualTo(1);

        // 空快照 → 删除。
        scheduler.sync(Snapshot.empty());
        assertThat(scheduler.trackedCount()).isZero();
    }

    @Test
    void syncCancelsWhenSubscriptionTurnsNonCron() {
        Subscription sub = cronSub(1L, "every-second", "* * * * * ?", Concurrent.SKIP);
        scheduler.sync(snapshot(sub));
        assertThat(scheduler.trackedCount()).isEqualTo(1);

        // 用一条 API 订阅（同 id）替换——按 sourceType 过滤后 CRON 集合为空 → 旧 CRON 调度被取消。
        Subscription apiSub = new Subscription(
                1L,
                "ns",
                100L,
                1,
                new Subscription.SourceRef(
                        "every-second", null, null, null, Set.of(), SourceType.API, null, null, Concurrent.SKIP),
                null,
                null);
        scheduler.sync(snapshot(apiSub));
        assertThat(scheduler.trackedCount()).isZero();
    }

    @Test
    void fireProducesTickEventWithCorrectMeta() throws Exception {
        Subscription sub = cronSub(7L, "nightly-9pm", "* * * * * ?", Concurrent.SKIP);
        scheduler.sync(snapshot(sub));

        TickEvent tick = listener.firstEventLatch.await(3, TimeUnit.SECONDS)
                ? listener.received.get(listener.received.size() - 1)
                : null;
        assertThat(tick).isNotNull();
        assertThat(tick.meta().source()).isEqualTo("cron:nightly-9pm");
        assertThat(tick.meta().cronName()).isEqualTo("nightly-9pm");
        assertThat(tick.meta().cronExpression()).isEqualTo("* * * * * ?");
        assertThat(tick.sourceTs()).isNotNull();
    }

    @Test
    void skipConcurrencyDropsOverlapFire() throws Exception {
        Subscription sub = cronSub(42L, "every-second-skip", "* * * * * ?", Concurrent.SKIP);
        scheduler.sync(snapshot(sub));

        // 等第一发真实触发——进入慢 listener 并 park（holdOpen 阻塞 fire）。
        listener.firstEventLatch.await(3, TimeUnit.SECONDS);
        // 此时第一发的 fire 仍卡在 listener 内（holdOpen=1，未 countDown），running=true。

        // 测试线程直接再调一次 fire：应被 SKIP，不调 listener，计数不增。
        CronScheduler.CronSub cronSub = CronScheduler.CronSub.from(sub);
        int before = listener.tickCount.get();
        scheduler.fire(42L, cronSub);
        int after = listener.tickCount.get();

        assertThat(after).isEqualTo(before);

        // 释放第一发，让 SES 线程退出（避免 leak）。
        listener.release();
    }

    @Test
    void allowConcurrencyDoesNotSkip() throws Exception {
        Subscription sub = cronSub(43L, "every-second-allow", "* * * * * ?", Concurrent.ALLOW);
        scheduler.sync(snapshot(sub));

        // 等第一发触发并 park（running=true 但 ALLOW 不查标志）。
        listener.firstEventLatch.await(3, TimeUnit.SECONDS);

        CronScheduler.CronSub cronSub = CronScheduler.CronSub.from(sub);
        int before = listener.tickCount.get();
        // 直接再调 fire——ALLOW 应越过 CAS 再次调用 listener。
        scheduler.fire(43L, cronSub);

        // listener 至少再增 1（ALLOW 不 skip）。等同步 listener 返回。
        listener.release();
        listener.release();
        int after = listener.tickCount.get();
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void nullConcurrentTreatedAsSkip() throws Exception {
        Subscription sub = cronSub(44L, "every-second-null", "* * * * * ?", null);
        scheduler.sync(snapshot(sub));
        listener.firstEventLatch.await(3, TimeUnit.SECONDS);

        CronScheduler.CronSub cronSub = CronScheduler.CronSub.from(sub);
        int before = listener.tickCount.get();
        scheduler.fire(44L, cronSub);
        int after = listener.tickCount.get();
        assertThat(after).isEqualTo(before); // SKIP 语义
        listener.release();
    }

    @Test
    void misfireDoesNotCatchUpOnSync() throws Exception {
        // "0 0 0 1 1 ?" = 每年 1 月 1 日 00:00——下一发在未来很久。sync 后立即不应有任何 fire。
        Subscription sub = cronSub(99L, "new-year", "0 0 0 1 1 ?", Concurrent.SKIP);
        scheduler.sync(snapshot(sub));
        assertThat(scheduler.trackedCount()).isEqualTo(1);

        // 等待短窗（远短于到下一年的间隔），断言未触发——证明只调度未来，无 catch-up。
        Thread.sleep(500L);
        assertThat(listener.tickCount.get()).isZero();
    }

    private static Snapshot snapshot(final Subscription sub) {
        return PipelineRegistry.Snapshot.loaded(Map.of(), List.of(sub));
    }

    private static Subscription cronSub(
            final Long id, final String name, final String expr, final Concurrent concurrent) {
        return new Subscription(
                id,
                "ns",
                100L,
                1,
                new Subscription.SourceRef(name, null, null, null, Set.of(), SourceType.CRON, expr, name, concurrent),
                null,
                null);
    }

    /** 捕获 TickEvent。holdOpen latch 让首次 fire park（验证 SKIP）。 */
    static final class CapturingListener implements EventListener {

        final List<TickEvent> received = new ArrayList<>();

        final AtomicInteger tickCount = new AtomicInteger();

        /** 第一发被投递时 countDown（测试线程 await 它）。 */
        final CountDownLatch firstEventLatch = new CountDownLatch(1);

        /** 变更表达式后第一发被投递时 countDown。 */
        final CountDownLatch firstEventLatch2 = new CountDownLatch(1);

        /** 第二轮 fire 窗口的 park 闸门（默认 1：第一次进入阻塞，测试 release() 才放行）。 */
        private volatile CountDownLatch holdOpen = new CountDownLatch(1);

        private volatile boolean parked = false;

        @Override
        public void onBatch(final List<Event> events) {
            for (Event e : events) {
                if (e instanceof TickEvent tick) {
                    received.add(tick);
                    int n = tickCount.incrementAndGet();
                    if (n == 1) {
                        firstEventLatch.countDown();
                    } else if (n == 2) {
                        firstEventLatch2.countDown();
                    }
                    parkIfHeld();
                }
            }
        }

        private void parkIfHeld() {
            CountDownLatch gate = holdOpen;
            if (!parked && gate.getCount() > 0) {
                parked = true;
                try {
                    gate.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void release() {
            holdOpen.countDown();
        }
    }
}
