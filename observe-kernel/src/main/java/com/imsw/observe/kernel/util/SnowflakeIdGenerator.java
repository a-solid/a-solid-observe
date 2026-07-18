package com.imsw.observe.kernel.util;

/**
 * Snowflake id 生成器：64-bit，趋势递增，跨实例唯一（不同 workerId/datacenterId 不冲突）。
 *
 * <p>布局（与 Twitter snowflake 一致）：
 * <ul>
 *   <li>1 bit 符号位（恒 0，正数）
 *   <li>41 bit 毫秒时间戳（相对自定义纪元，约 69 年）
 *   <li>5 bit datacenterId（0-31）
 *   <li>5 bit workerId（0-31）
 *   <li>12 bit 同毫秒序列号（0-4095）
 * </ul>
 *
 * <p>线程安全：{@link #next()} synchronized，保证同毫秒序列号不重。
 * 一期单 worker，workerId 硬编码 1；多 worker 时由协调分配 workerId（二期）。
 */
public final class SnowflakeIdGenerator {

    private static final long EPOCH = 1_704_067_200_000L; // 2024-01-01T00:00:00Z 毫秒

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS); // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS); // 31

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS); // 4095

    private final long workerId;
    private final long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(final long workerId, final long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId must be between 0 and " + MAX_WORKER_ID + " but was " + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    "datacenterId must be between 0 and " + MAX_DATACENTER_ID + " but was " + datacenterId);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long next() {
        long now = timeMillis();
        if (now < lastTimestamp) {
            // 时钟回拨：等待到上次时间。一期容忍小幅回拨。
            now = lastTimestamp;
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                // 同毫秒序列耗尽，等下一毫秒
                now = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(final long lastTimestamp) {
        long now = timeMillis();
        while (now <= lastTimestamp) {
            now = timeMillis();
        }
        return now;
    }

    private static long timeMillis() {
        return System.currentTimeMillis();
    }
}
