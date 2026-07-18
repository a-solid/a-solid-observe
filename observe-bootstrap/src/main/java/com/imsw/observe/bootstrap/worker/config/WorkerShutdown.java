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

@Component
@ConditionalOnProperty(prefix = "observe.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerShutdown {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerShutdown.class);

    private static final long GRACE_SECONDS = 60;

    private final ThreadPoolExecutor runnerPool;

    private final java.util.List<Source> sources;

    private final DelayedEventStore delayedStore;

    public WorkerShutdown(
            final ThreadPoolExecutor runnerPool,
            final java.util.List<Source> sources,
            final DelayedEventStore delayedStore) {
        this.runnerPool = runnerPool;
        this.sources = sources;
        this.delayedStore = delayedStore;
    }

    @PreDestroy
    public void shutdown() {
        LOG.info("graceful shutdown begin");
        for (Source source : sources) {
            try {
                source.stop();
            } catch (RuntimeException e) {
                LOG.warn("source {} stop failed", source.getClass().getSimpleName(), e);
            }
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
