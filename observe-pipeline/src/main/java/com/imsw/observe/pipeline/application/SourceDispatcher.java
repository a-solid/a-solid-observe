package com.imsw.observe.pipeline.application;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.domain.Pipeline;

public final class SourceDispatcher implements EventListener {

    private static final Logger LOG = LoggerFactory.getLogger(SourceDispatcher.class);

    private final SubscriptionMatcher matcher;

    private final PipelineRunner runner;

    private final ThreadPoolExecutor runnerPool;

    private final DelayedActionHandler delayedActionHandler;

    public SourceDispatcher(
            final SubscriptionMatcher matcher,
            final PipelineRunner runner,
            final ThreadPoolExecutor runnerPool,
            final DelayedActionHandler delayedActionHandler) {
        this.matcher = matcher;
        this.runner = runner;
        this.runnerPool = runnerPool;
        this.delayedActionHandler = delayedActionHandler;
    }

    /**
     * Dispatch a batch of events to matched pipelines.
     *
     * <p>Contract: this method throws {@link java.util.concurrent.RejectedExecutionException} when
     * the runner pool is saturated. Callers that require at-least-once semantics (e.g. the IBM MQ
     * source) rely on this propagation to treat the batch as failed and avoid acking a dropped
     * event.
     */
    @Override
    public void onBatch(final List<Event> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (Event event : events) {
            dispatch(event);
        }
    }

    private void dispatch(final Event event) {
        List<SubscriptionMatcher.MatchedSubscription> matched = matcher.match(event);
        if (matched.isEmpty()) {
            return;
        }
        for (SubscriptionMatcher.MatchedSubscription m : matched) {
            Pipeline pipeline = m.pipeline();
            String subscriptionId = m.subscription().id();
            if (delayedActionHandler.handle(m.subscription(), event, pipeline)) {
                continue;
            }
            // 让 RejectedExecutionException 传播到调用方 (如 IbmMqCdcSource.flush) 以保证 at-least-once.
            runnerPool.execute(() -> {
                try {
                    runner.run(pipeline, event, subscriptionId);
                } catch (RuntimeException e) {
                    LOG.warn("pipeline {} (subscription {}) execution threw", pipeline.id(), subscriptionId, e);
                }
            });
        }
    }
}
