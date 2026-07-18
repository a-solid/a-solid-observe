package com.imsw.observe.bootstrap.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "observe.worker")
public class WorkerProperties {

    private int runnerCore = 16;

    private int runnerMax = 32;

    private int runnerQueue = 1000;

    /**
     * CronScheduler 调度线程池大小（ADR-0007 B4）。每条 CRON 订阅一个调度句柄，自递归投递；
     * 线程主要承载"算下一发 + 投 TickEvent + re-arm"，IO 由 runnerPool 承担，故 4 通常足够。
     */
    private int cronSchedulerPoolSize = 4;

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

    public int getCronSchedulerPoolSize() {
        return cronSchedulerPoolSize;
    }

    public void setCronSchedulerPoolSize(final int cronSchedulerPoolSize) {
        this.cronSchedulerPoolSize = cronSchedulerPoolSize;
    }

    public int getDelayedPoolSize() {
        return delayedPoolSize;
    }

    public void setDelayedPoolSize(final int delayedPoolSize) {
        this.delayedPoolSize = delayedPoolSize;
    }
}
