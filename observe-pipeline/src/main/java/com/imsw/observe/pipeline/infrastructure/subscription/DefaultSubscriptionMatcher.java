package com.imsw.observe.pipeline.infrastructure.subscription;

import java.util.ArrayList;
import java.util.List;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.SubscriptionMatcher;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

public final class DefaultSubscriptionMatcher implements SubscriptionMatcher {

    private final PipelineRegistry registry;

    public DefaultSubscriptionMatcher(final PipelineRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<MatchedSubscription> match(final Event event) {
        List<MatchedSubscription> matched = new ArrayList<>();
        if (!registry.isLoaded() || event == null || event.meta() == null) {
            return matched;
        }
        PipelineRegistry.Snapshot snapshot = registry.snapshot();
        for (Subscription sub :
                snapshot.subscriptionsFor(event.meta().db(), event.meta().table())) {
            if (!matchesSource(sub, event)) {
                continue;
            }
            if (sub.fieldFilter() != null && !sub.fieldFilter().matches(event)) {
                continue;
            }
            Pipeline pipeline = snapshot.pipelineById(sub.pipelineId());
            if (pipeline == null || pipeline.version() != sub.pipelineVersion()) {
                continue;
            }
            matched.add(new MatchedSubscription(sub, pipeline));
        }
        return matched;
    }

    @Override
    public boolean isLoaded() {
        return registry.isLoaded();
    }

    private static boolean matchesSource(final Subscription sub, final Event event) {
        if (sub.source() == null) {
            return false;
        }
        if (sub.source().opTypes() != null
                && !sub.source().opTypes().isEmpty()
                && !sub.source().opTypes().contains(event.op())) {
            return false;
        }
        if (sub.source().sourceType() != null
                && event.meta().sourceType() != null
                && sub.source().sourceType() != event.meta().sourceType()) {
            return false;
        }
        return true;
    }
}
