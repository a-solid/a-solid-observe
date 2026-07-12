package com.imsw.observe.pipeline.application;

import java.util.List;

import com.imsw.observe.kernel.event.model.Event;

public interface SubscriptionMatcher {

    List<MatchedSubscription> match(Event event);

    boolean isLoaded();

    record MatchedSubscription(
            com.imsw.observe.pipeline.domain.subscription.Subscription subscription,
            com.imsw.observe.pipeline.domain.Pipeline pipeline) {}
}
