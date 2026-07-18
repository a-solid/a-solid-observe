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
     * CronSource 调度线程池大小（ADR-0007 B4）。每条 CRON 订阅一个调度句柄，自递归投递；
     * 线程主要承载"算下一发 + 投 TickEvent + re-arm"，IO 由 runnerPool 承担，故 4 通常足够。
     */
    private int cronSchedulerPoolSize = 4;

    /**
     * SourceDispatcher 内部队列容量（B9 §3.2）。Source 端 {@code onEvent} 阻塞入队——队列满时
     * 反压到上游（MQ 不 ack / Cron 延后 / Api 阻塞）。
     */
    private int dispatchQueueSize = 1000;

    /**
     * SourceDispatcher 分发线程数（B9 §3.2）。线程循环 take→match→阻塞提交 runnerPool。
     */
    private int dispatchThreads = 2;

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

    public int getDispatchQueueSize() {
        return dispatchQueueSize;
    }

    public void setDispatchQueueSize(final int dispatchQueueSize) {
        this.dispatchQueueSize = dispatchQueueSize;
    }

    public int getDispatchThreads() {
        return dispatchThreads;
    }

    public void setDispatchThreads(final int dispatchThreads) {
        this.dispatchThreads = dispatchThreads;
    }

    public int getDelayedPoolSize() {
        return delayedPoolSize;
    }

    public void setDelayedPoolSize(final int delayedPoolSize) {
        this.delayedPoolSize = delayedPoolSize;
    }
}
