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
