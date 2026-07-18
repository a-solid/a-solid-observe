package com.imsw.observe.bootstrap.worker.config;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.imsw.observe.pipeline.application.DelayedEventStore;
import com.imsw.observe.pipeline.application.Source;
import com.imsw.observe.pipeline.application.SourceDispatcher;

@Component
@ConditionalOnProperty(prefix = "observe.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerShutdown {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerShutdown.class);

    private static final long GRACE_SECONDS = 60;

    private final ThreadPoolExecutor runnerPool;

    private final java.util.List<Source> sources;

    private final DelayedEventStore delayedStore;

    private final SourceDispatcher dispatcher;

    /**
     * 统一 source 生命周期（B9 §4）：CronSource 也实现了 {@link Source}，被 Spring 收进
     * {@code List<Source>}——无需再单独注入 CronScheduler/cron 句柄。
     */
    public WorkerShutdown(
            final ThreadPoolExecutor runnerPool,
            final java.util.List<Source> sources,
            final DelayedEventStore delayedStore,
            final SourceDispatcher dispatcher) {
        this.runnerPool = runnerPool;
        this.sources = sources;
        this.delayedStore = delayedStore;
        this.dispatcher = dispatcher;
    }

    @PreDestroy
    public void shutdown() {
        LOG.info("graceful shutdown begin");
        // 停所有 Source（含 CronSource：取消全部 cron 句柄 + shutdown 其 SES），避免关闭期间仍触发新
        // pipeline 执行。CronSource.stop 等价旧 CronScheduler.shutdown。
        for (Source source : sources) {
            try {
                source.stop();
            } catch (RuntimeException e) {
                LOG.warn("source {} stop failed", source.getClass().getSimpleName(), e);
            }
        }
        // 停 dispatcher：drain 内部队列剩余事件（match+提交到 runnerPool）后中断分发线程。
        // dispatcher 的 destroyMethod=stop 也会再调一次（幂等）；此处主动提前 drain，保证 runnerPool
        // 关闭前剩余事件已入 runnerPool（at-least-once 由 MQ 重投兜底）。
        try {
            dispatcher.stop();
        } catch (RuntimeException e) {
            LOG.warn("dispatcher stop failed", e);
        }
        runnerPool.shutdown();
        try {
            if (!runnerPool.awaitTermination(GRACE_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("runner pool did not terminate in {}s; forcing shutdown", GRACE_SECONDS);
                runnerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runnerPool.shutdownNow();
        }
        try {
            delayedStore.shutdown();
        } catch (RuntimeException e) {
            LOG.warn("delayed store shutdown failed", e);
        }
        LOG.info("graceful shutdown done");
    }
}
