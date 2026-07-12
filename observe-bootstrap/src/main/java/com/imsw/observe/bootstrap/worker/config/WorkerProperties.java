package com.imsw.observe.bootstrap.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "observe.worker")
public class WorkerProperties {

    private int runnerCore = 16;

    private int runnerMax = 32;

    private int runnerQueue = 1000;

    private int cronPoolSize = 2;

    private long cronPeriodMillis = 60_000L;

    private int delayedPoolSize = 4;

    public int getRunnerCore() {
        return runnerCore;
    }

    public void setRunnerCore(final int runnerCore) {
        this.runnerCore = runnerCore;
    }

    public int getRunnerMax() {
        return runnerMax;
    }

    public void setRunnerMax(final int runnerMax) {
        this.runnerMax = runnerMax;
    }

    public int getRunnerQueue() {
        return runnerQueue;
    }

    public void setRunnerQueue(final int runnerQueue) {
        this.runnerQueue = runnerQueue;
    }

    public int getCronPoolSize() {
        return cronPoolSize;
    }

    public void setCronPoolSize(final int cronPoolSize) {
        this.cronPoolSize = cronPoolSize;
    }

    public long getCronPeriodMillis() {
        return cronPeriodMillis;
    }

    public void setCronPeriodMillis(final long cronPeriodMillis) {
        this.cronPeriodMillis = cronPeriodMillis;
    }

    public int getDelayedPoolSize() {
        return delayedPoolSize;
    }

    public void setDelayedPoolSize(final int delayedPoolSize) {
        this.delayedPoolSize = delayedPoolSize;
    }
}
