package com.imsw.observe.kernel.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    void nextReturnsPositiveTrendAscending() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1L, 0L);
        long a = gen.next();
        long b = gen.next();
        long c = gen.next();
        assertThat(a).isPositive();
        assertThat(b).isGreaterThan(a);
        assertThat(c).isGreaterThan(b);
    }

    @Test
    void nextIsUniqueAcrossManyCallsOneThread() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1L, 0L);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(gen.next())).isTrue();
        }
    }

    @Test
    void nextIsUniqueAcrossThreadsSharingOneGenerator() throws InterruptedException {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1L, 0L);
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        int threads = 8;
        int perThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        long id = gen.next();
                        assertThat(seen.add(id)).as("duplicate id %d", id).isTrue();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(seen).hasSize(threads * perThread);
    }

    @Test
    void differentWorkerIdsProduceDisjointRanges() {
        SnowflakeIdGenerator gen1 = new SnowflakeIdGenerator(1L, 0L);
        SnowflakeIdGenerator gen2 = new SnowflakeIdGenerator(2L, 0L);
        Set<Long> from1 = new HashSet<>();
        Set<Long> from2 = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            from1.add(gen1.next());
            from2.add(gen2.next());
        }
        for (Long id : from2) {
            assertThat(from1).doesNotContain(id);
        }
    }
}
