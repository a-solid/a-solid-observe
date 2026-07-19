package com.imsw.observe.bootstrap.worker.config;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.jms.JMSException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.msg.client.wmq.common.CommonConstants;
import com.imsw.observe.bootstrap.worker.source.IbmMqCdcSource;
import com.imsw.observe.bootstrap.worker.source.IbmMqXmlParser;
import com.imsw.observe.bootstrap.worker.source.InMemoryCdcSource;
import com.imsw.observe.config.application.ConfigLoader;
import com.imsw.observe.config.application.PipelineHotReloader;
import com.imsw.observe.config.application.PipelineRegistryLoader;
import com.imsw.observe.pipeline.application.CronSource;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.application.SourceDispatcher;
import com.imsw.observe.pipeline.application.SubscriptionMatcher;
import com.imsw.observe.pipeline.infrastructure.source.ApiSource;

@Configuration
@ConditionalOnProperty(prefix = "observe.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerConfig.class);

    @Bean
    public PipelineRegistry pipelineRegistry(
            final org.springframework.beans.factory.ObjectProvider<
                            com.imsw.observe.pipeline.application.SnapshotListener>
                    snapshotListeners) {
        // 注入 SnapshotListener（运行时 = CronSource）。registry.replace swap 后自动通知——
        // 取代过去散在此处的手动 cronSource.sync（ADR-0007：CronSource 作为 registry 观察者）。
        // ObjectProvider 破构造期循环：registry → CronSource → dispatcher → matcher → registry。
        return new PipelineRegistry(snapshotListeners);
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
    public com.imsw.observe.pipeline.application.DelayedEventStore delayedEventStore(final WorkerProperties props) {
        java.util.concurrent.ScheduledExecutorService ses =
                java.util.concurrent.Executors.newScheduledThreadPool(props.getDelayedPoolSize());
        return new com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore(ses);
    }

    @Bean
    public com.imsw.observe.pipeline.application.DelayedActionHandler delayedActionHandler(
            final com.imsw.observe.pipeline.application.DelayedEventStore store,
            final org.springframework.beans.factory.ObjectProvider<SourceDispatcher> dispatcherProvider) {
        // dispatcher 用 ObjectProvider 延迟解析——破构造期循环（dispatcher 持有 handler、handler 持有
        // dispatcher=EventListener）。handler 构造时不解析 dispatcher，fire 时才 get()——此时 dispatcher
        // bean 已就绪。删了旧 setDispatcher + throw 占位炸弹。
        return new com.imsw.observe.pipeline.application.DelayedActionHandler(
                store, () -> (com.imsw.observe.pipeline.application.EventListener) dispatcherProvider.getObject());
    }

    /**
     * 单事件分发器（B9 §3.2）。内部有界队列 + N 分发线程 + 阻塞 runnerPool 提交（不丢事件）。
     *
     * <p>{@code destroyMethod = "stop"}：容器关闭时 drain 队列 + 中断分发线程（at-least-once 由 MQ
     * 重投兜底）。{@code initMethod} 在 Spring 完成依赖注入后启动分发线程——保证 runnerPool / matcher
     * / delayedHandler 等协作者已就绪。
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public SourceDispatcher sourceDispatcher(
            final SubscriptionMatcher matcher,
            final PipelineRunner runner,
            final ThreadPoolExecutor pool,
            final com.imsw.observe.pipeline.application.DelayedActionHandler delayedActionHandler,
            final WorkerProperties props) {
        // runnerPool 在途上限 = 工作队列容量 + 最大线程数；饱和时分发线程阻塞在 Semaphore.acquire。
        int runnerInFlight = props.getRunnerQueue() + props.getRunnerMax();
        SourceDispatcher dispatcher = new SourceDispatcher(
                matcher,
                runner,
                pool,
                delayedActionHandler,
                props.getDispatchQueueSize(),
                props.getDispatchThreads(),
                runnerInFlight);
        // dispatcher 注入 handler 已在 delayedActionHandler bean 内用 ObjectProvider 延迟解析（破循环），
        // 无需此处手动 setDispatcher。fire 时 handler 解析 dispatcher.onEvent 灌回队列（ADR-0006 addendum）。
        return dispatcher;
    }

    @Bean
    public InMemoryCdcSource inMemoryCdcSource(final SourceDispatcher dispatcher) {
        InMemoryCdcSource source = new InMemoryCdcSource();
        source.start(dispatcher::onEvent);
        return source;
    }

    @Bean
    @ConditionalOnProperty(prefix = "observe.worker.ibm-mq", name = "enabled", havingValue = "true")
    public IbmMqCdcSource ibmMqCdcSource(final SourceDispatcher dispatcher, final IbmMqProperties props) {
        MQConnectionFactory cf = new MQConnectionFactory();
        try {
            cf.setHostName(props.getHost());
            cf.setPort(props.getPort());
            cf.setQueueManager(props.getQueueManager());
            cf.setChannel(props.getChannel());
            cf.setTransportType(CommonConstants.WMQ_CM_CLIENT);
        } catch (JMSException e) {
            throw new IllegalStateException("cannot configure IBM MQ connection factory", e);
        }
        IbmMqXmlParser parser = new IbmMqXmlParser();
        IbmMqCdcSource source = new IbmMqCdcSource(cf, props.getQueue(), parser);
        source.start(dispatcher::onEvent);
        return source;
    }

    @Bean
    public ApiSource apiSource(final SourceDispatcher dispatcher) {
        ApiSource source = new ApiSource();
        source.start(dispatcher::onEvent);
        return source;
    }

    /**
     * CronSource 专用调度线程池（ADR-0007 B4）。每条 CRON 订阅一个 schedule 句柄自递归投递；
     * {@code destroyMethod=shutdown} 在容器关闭时终止线程池（{@link CronSource#stop()} 内部也会
     * 调一次 {@code shutdown()}，幂等）。
     */
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService cronSchedulerPool(final WorkerProperties props) {
        return Executors.newScheduledThreadPool(props.getCronSchedulerPoolSize(), r -> {
            Thread t = new Thread(r, "cron-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 每订阅 cron 调度源（ADR-0007 + B9 §4 对齐 {@link com.imsw.observe.pipeline.application.Source}）。
     * 监听 {@link PipelineRegistry} 快照变化：{@link CronSource#sync} diff 出新增/变更/删除的 CRON 订阅
     * 并起停调度句柄。运行时 listener = {@code SourceDispatcher::onEvent}，到点产出
     * {@link com.imsw.observe.kernel.event.model.TickEvent} 由 matcher 按 source（= mq）路由。
     *
     * <p>{@code start(dispatcher::onEvent)} 注入 listener——与其他 Source（IbmMqCdcSource/ApiSource/
     * InMemoryCdcSource）一致；Spring 自动把本 bean 收进 {@code List<Source>}，由
     * {@link WorkerShutdown} 统一 {@code stop()}。
     */
    @Bean
    public CronSource cronSource(final ScheduledExecutorService cronSchedulerPool, final SourceDispatcher dispatcher) {
        CronSource source = new CronSource(cronSchedulerPool);
        source.start(dispatcher::onEvent);
        return source;
    }

    @Bean
    public ApplicationRunner workerColdStart(final ConfigLoader configLoader) {
        // 冷启动：load 落库快照。registry.replace 内置 SnapshotListener 通知——CronSource 作为 listener
        // 自动收到新 snapshot 起订阅级调度句柄（无需此处手动 sync）。CronSource 此时已 start
        // （bean 装配阶段注入 listener），sync 触发的 fire 才能正确投递 TickEvent。
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
            // reloader.refresh → registry.replace → 内置 SnapshotListener 通知 CronSource 起停调度（ADR-0007）。
            reloader.refresh();
        }
    }
}
