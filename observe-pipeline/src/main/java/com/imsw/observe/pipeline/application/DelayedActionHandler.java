package com.imsw.observe.pipeline.application;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Subscription;
import com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore;

public final class DelayedActionHandler {

    private final InMemoryDelayedEventStore store;

    public DelayedActionHandler(final InMemoryDelayedEventStore store) {
        this.store = store;
    }

    public boolean handle(final Subscription subscription, final Event event, final Pipeline pipeline) {
        Action action = subscription.action();
        if (action instanceof Action.Schedule schedule) {
            store.schedule(subscription, event, pipeline, schedule.delay(), schedule.correlationKeyPath());
            return true;
        }
        if (action instanceof Action.Cancel cancel) {
            store.cancel(subscription, event, cancel.correlationKeyPath());
            return true;
        }
        return false;
    }
}
