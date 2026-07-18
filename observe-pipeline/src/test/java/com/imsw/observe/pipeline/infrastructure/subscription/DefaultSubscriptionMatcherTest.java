package com.imsw.observe.pipeline.infrastructure.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.SubscriptionMatcher;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Condition;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

class DefaultSubscriptionMatcherTest {

    @Test
    void returnsEmptyWhenNotLoaded() {
        PipelineRegistry registry = new PipelineRegistry();
        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);
        assertThat(matcher.isLoaded()).isFalse();
        assertThat(matcher.match(event("trade_db", "orders", Op.INSERT, Map.of())))
                .isEmpty();
    }

    @Test
    void matchesByDbTableOpAndFieldFilter() {
        Pipeline pipeline = pipeline(1L, 1);
        Subscription sub = new Subscription(
                10L,
                "smoke",
                1L,
                1,
                new Subscription.SourceRef("mq", "topic", "trade_db", "orders", Set.of(Op.INSERT), SourceType.CDC),
                new Condition.Compare("after.status", Condition.Compare.Op.EQ, "PAID"),
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));

        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);

        Event hit = event("trade_db", "orders", Op.INSERT, Map.of("status", "PAID"));
        Event wrongStatus = event("trade_db", "orders", Op.INSERT, Map.of("status", "NEW"));
        Event wrongOp = event("trade_db", "orders", Op.UPDATE, Map.of("status", "PAID"));
        Event wrongTable = event("trade_db", "payments", Op.INSERT, Map.of("status", "PAID"));

        assertThat(matcher.match(hit)).hasSize(1);
        assertThat(matcher.match(wrongStatus)).isEmpty();
        assertThat(matcher.match(wrongOp)).isEmpty();
        assertThat(matcher.match(wrongTable)).isEmpty();
    }

    @Test
    void skipsWhenPipelineVersionMismatch() {
        Pipeline pipeline = pipeline(1L, 2);
        Subscription sub = new Subscription(
                10L,
                "smoke",
                1L,
                1,
                new Subscription.SourceRef(null, null, "trade_db", "orders", Set.of(), SourceType.CDC),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));

        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);
        assertThat(matcher.match(event("trade_db", "orders", Op.INSERT, Map.of())))
                .isEmpty();
    }

    private Pipeline pipeline(final Long id, final int version) {
        return new Pipeline(
                id,
                "smoke",
                version,
                "team",
                "app",
                Map.of(),
                "name",
                Pipeline.Status.PUBLISHED,
                List.of(),
                Instant.now(),
                Instant.now(),
                0.0);
    }

    private Event event(final String db, final String table, final Op op, final Map<String, Object> after) {
        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "mq", db, table, Map.of());
        return new Event(meta, Map.of(), after, op, Instant.now());
    }
}
