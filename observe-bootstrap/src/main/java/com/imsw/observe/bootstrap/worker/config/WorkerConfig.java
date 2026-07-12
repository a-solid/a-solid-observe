package com.imsw.observe.bootstrap.worker.config;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.imsw.observe.bootstrap.worker.source.InMemoryCdcMessageSource;
import com.imsw.observe.config.application.ConfigLoader;
import com.imsw.observe.config.application.PipelineHotReloader;
import com.imsw.observe.config.application.PipelineRegistryLoader;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.application.SourceDispatcher;
import com.imsw.observe.pipeline.application.SubscriptionMatcher;
import com.imsw.observe.pipeline.infrastructure.source.ApiSource;
import com.imsw.observe.pipeline.infrastructure.source.CdcMqSource;
import com.imsw.observe.pipeline.infrastructure.source.CronSource;

@Configuration
@ConditionalOnProperty(prefix = "observe.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerConfig.class);

    @Bean
    public PipelineRegistry pipelineRegistry() {
        return new PipelineRegistry();
    }

    @Bean
    public PipelineRegistryLoader pipelineRegistryLoader(
            final com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository
                    pipelineDefinitionRepository,
            final com.imsw.observe.config.infrastructure.persistence.PipelineVersionRepository
                    pipelineVersionRepository,
            final com.imsw.observe.config.infrastructure.persistence.SubscriptionRepository subscriptionRepository,
            final com.imsw.observe.config.infrastructure.ConditionCodec conditionCodec) {
        return new PipelineRegistryLoader(
                pipelineDefinitionRepository, pipelineVersionRepository, subscriptionRepository, conditionCodec);
    }

    @Bean
    public ConfigLoader configLoader(final PipelineRegistry registry, final PipelineRegistryLoader loader) {
        return new ConfigLoader(registry, loader);
    }

    @Bean
    public PipelineHotReloader pipelineHotReloader(
            final PipelineRegistry registry, final PipelineRegistryLoader loader) {
        return new PipelineHotReloader(registry, loader);
    }

    @Bean
    public ThreadPoolExecutor pipelineRunnerPool(final WorkerProperties props) {
        return new ThreadPoolExecutor(
                props.getRunnerCore(),
                props.getRunnerMax(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(props.getRunnerQueue()));
    }

    @Bean(destroyMethod = "shutdown")
    public com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore delayedEventStore(
            final PipelineRunner runner, final WorkerProperties props) {
        java.util.concurrent.ScheduledExecutorService ses =
                java.util.concurrent.Executors.newScheduledThreadPool(props.getDelayedPoolSize());
        return new com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore(ses, runner);
    }

    @Bean
    public com.imsw.observe.pipeline.application.DelayedActionHandler delayedActionHandler(
            final com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore store) {
        return new com.imsw.observe.pipeline.application.DelayedActionHandler(store);
    }

    @Bean
    public SourceDispatcher sourceDispatcher(
            final SubscriptionMatcher matcher,
            final PipelineRunner runner,
            final ThreadPoolExecutor pool,
            final com.imsw.observe.pipeline.application.DelayedActionHandler delayedActionHandler) {
        return new SourceDispatcher(matcher, runner, pool, delayedActionHandler);
    }

    @Bean
    public InMemoryCdcMessageSource cdcMessageSource() {
        return new InMemoryCdcMessageSource();
    }

    @Bean
    public CdcMqSource cdcMqSource(final InMemoryCdcMessageSource cdcMessageSource, final SourceDispatcher dispatcher) {
        CdcMqSource source = new CdcMqSource(cdcMessageSource);
        source.start(dispatcher::onBatch);
        return source;
    }

    @Bean
    public ApiSource apiSource(final SourceDispatcher dispatcher) {
        ApiSource source = new ApiSource();
        source.start(dispatcher::onBatch);
        return source;
    }

    @Bean
    public CronSource cronSource(final SourceDispatcher dispatcher, final WorkerProperties props) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(props.getCronPoolSize());
        CronSource source = new CronSource(scheduler, "default", props.getCronPeriodMillis());
        source.start(dispatcher::onBatch);
        return source;
    }

    @Bean
    public ApplicationRunner workerColdStart(final ConfigLoader configLoader) {
        return args -> configLoader.load();
    }

    @Bean
    public HotReloaderScheduler hotReloaderScheduler(final PipelineHotReloader reloader) {
        return new HotReloaderScheduler(reloader);
    }

    public static class HotReloaderScheduler {

        private final PipelineHotReloader reloader;

        HotReloaderScheduler(final PipelineHotReloader reloader) {
            this.reloader = reloader;
        }

        @Scheduled(fixedDelay = 30_000L)
        public void refresh() {
            reloader.refresh();
        }
    }
}
