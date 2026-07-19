package com.imsw.observe.pipeline.infrastructure.delayed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.imsw.observe.pipeline.application.DelayedEventStore;

class InMemoryDelayedEventStoreTest {

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(2);

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void scheduleExecutesTaskAfterDelay() throws Exception {
        DelayedEventStore store = new InMemoryDelayedEventStore(scheduler);
        AtomicInteger fires = new AtomicInteger();

        store.schedule("k1", fires::incrementAndGet, Duration.ofMillis(50));

        assertThat(await(fires, 1)).isTrue();
        assertThat(store.pendingCount()).isZero(); // fire 后 finally 清 map
        store.shutdown();
    }

    @Test
    void scheduleReplacesSameKey() throws Exception {
        DelayedEventStore store = new InMemoryDelayedEventStore(scheduler);
        AtomicInteger fires = new AtomicInteger();

        store.schedule("k1", fires::incrementAndGet, Duration.ofMillis(200));
        store.schedule("k1", fires::incrementAndGet, Duration.ofMillis(200)); // 老 task cancel(false)

        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(await(fires, 1)).isTrue();
        assertThat(fires.get()).isLessThanOrEqualTo(1); // 老 task 不应执行（cancel(false)）
        store.shutdown();
    }

    @Test
    void cancelRemovesPending() {
        DelayedEventStore store = new InMemoryDelayedEventStore(scheduler);
        AtomicInteger fires = new AtomicInteger();

        store.schedule("k1", fires::incrementAndGet, Duration.ofSeconds(5));
        assertThat(store.pendingCount()).isEqualTo(1);

        store.cancel("k1");
        assertThat(store.pendingCount()).isZero();
        store.cancel("k1"); // 幂等
        store.shutdown();
    }

    /**
     * D5（delayed-redesign spec）：cancel 遇到 in-flight fire 落空。fire 已被 SES 线程取出执行后，
     * ScheduledFuture.cancel(false) 不能停掉正在跑的任务——cancel 此时是 no-op（map 已被 finally 清）。
     * 接受这个语义：cancel 只撤销"还没到时间的"。
     */
    @Test
    void cancelDuringInflightFireLetsFireComplete() throws Exception {
        DelayedEventStore store = new InMemoryDelayedEventStore(scheduler);
        java.util.concurrent.CountDownLatch fireStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch fireRelease = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger fires = new AtomicInteger();

        // fire 任务：先 park（模拟慢执行），让测试线程在 fire 进行中调 cancel。
        store.schedule(
                "k1",
                () -> {
                    fires.incrementAndGet();
                    fireStarted.countDown();
                    try {
                        fireRelease.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                Duration.ofMillis(50));

        // 等 fire 真正开始（进入 park）。
        assertThat(fireStarted.await(1, TimeUnit.SECONDS)).isTrue();
        // 此时 fire 进行中——cancel 调用应幂等 no-op（map 已被 finally 清掉）。
        store.cancel("k1");
        // pendingCount=0（map 已清）但 fires 已=1，说明 fire 真的执行了，cancel 没能停它。
        assertThat(store.pendingCount()).isZero();
        assertThat(fires.get()).isEqualTo(1);

        // 释放 fire，让它干净退出。
        fireRelease.countDown();
        store.shutdown();
    }

    private static boolean await(final AtomicInteger counter, final int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (counter.get() >= expected) {
                return true;
            }
            Thread.sleep(20);
        }
        return counter.get() >= expected;
    }
}
