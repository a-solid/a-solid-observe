package com.imsw.observe.pipeline.infrastructure.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.event.model.ApiEvent;
import com.imsw.observe.kernel.event.model.ApiMeta;
import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.CdcMeta;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.DelayedEvent;
import com.imsw.observe.kernel.event.model.DelayedMeta;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.event.model.TickEvent;
import com.imsw.observe.kernel.event.model.TickMeta;
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
        assertThat(matcher.match(cdcEvent("trade_db", "orders", CdcOp.INSERT, Map.of())))
                .isEmpty();
    }

    @Test
    void matchesCdcByDbTableOpAndFieldFilter() {
        Pipeline pipeline = pipeline(1L, 1);
        Subscription sub = new Subscription(
                10L,
                "smoke",
                java.util.List.of(1L),
                new Subscription.SourceRef(
                        "mq", "topic", "trade_db", "orders", Set.of(CdcOp.INSERT), SourceType.CDC, null, null, null),
                new Condition.Compare("after.status", Condition.Compare.Op.EQ, "PAID"),
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));

        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);

        Event hit = cdcEvent("trade_db", "orders", CdcOp.INSERT, Map.of("status", "PAID"));
        Event wrongStatus = cdcEvent("trade_db", "orders", CdcOp.INSERT, Map.of("status", "NEW"));
        Event wrongOp = cdcEvent("trade_db", "orders", CdcOp.UPDATE, Map.of("status", "PAID"));
        Event wrongTable = cdcEvent("trade_db", "payments", CdcOp.INSERT, Map.of("status", "PAID"));

        assertThat(matcher.match(hit)).hasSize(1);
        assertThat(matcher.match(wrongStatus)).isEmpty();
        assertThat(matcher.match(wrongOp)).isEmpty();
        assertThat(matcher.match(wrongTable)).isEmpty();
    }

    @Test
    void matchesTickBySourceName() {
        Pipeline pipeline = pipeline(1L, 1);
        // Cron 订阅：source name "nightly-sync" 复用 SourceRef.mq 槽（见 PipelineRegistry.Snapshot 注释）。
        Subscription sub = new Subscription(
                11L,
                "smoke",
                java.util.List.of(1L),
                new Subscription.SourceRef(
                        "nightly-sync", null, null, null, Set.of(), SourceType.CRON, null, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));

        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);

        TickEvent hit = new TickEvent(new TickMeta("nightly-sync", "nightly-sync", null, Map.of()), Instant.now());
        TickEvent wrongName = new TickEvent(new TickMeta("hourly-sync", "hourly-sync", null, Map.of()), Instant.now());

        assertThat(matcher.match(hit)).hasSize(1);
        assertThat(matcher.match(wrongName)).isEmpty();
    }

    @Test
    void matchesApiBySourceName() {
        Pipeline pipeline = pipeline(1L, 1);
        // Api 订阅：source name "order-webhook" 复用 SourceRef.mq 槽。
        Subscription sub = new Subscription(
                12L,
                "smoke",
                java.util.List.of(1L),
                new Subscription.SourceRef(
                        "order-webhook", null, null, null, Set.of(), SourceType.API, null, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, pipeline), List.of(sub)));

        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);

        ApiEvent hit = new ApiEvent(
                new ApiMeta("order-webhook", "order-webhook", Map.of()), Map.of("amt", 100), Instant.now());
        ApiEvent wrongName =
                new ApiEvent(new ApiMeta("refund-webhook", "refund-webhook", Map.of()), Map.of(), Instant.now());

        assertThat(matcher.match(hit)).hasSize(1);
        assertThat(matcher.match(wrongName)).isEmpty();
    }

    @Test
    void returnsOnlyExistingPipelinesWhenSomeMissing() {
        // 扇出：pipelineIds 含未加载的 id（2）→ 只返回存在的（p1）。
        Pipeline p1 = pipeline(1L, 1);
        Subscription sub = new Subscription(
                10L,
                "smoke",
                java.util.List.of(1L, 2L),
                new Subscription.SourceRef(
                        null, null, "trade_db", "orders", Set.of(), SourceType.CDC, null, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, p1), List.of(sub)));

        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);
        List<SubscriptionMatcher.MatchedSubscription> matched =
                matcher.match(cdcEvent("trade_db", "orders", CdcOp.INSERT, Map.of()));
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).pipelines()).containsExactly(p1);
    }

    @Test
    void skipsWhenAllPipelinesMissing() {
        // 扇出：pipelineIds 全部不在 registry → matched 为空。
        Subscription sub = new Subscription(
                10L,
                "smoke",
                java.util.List.of(99L),
                new Subscription.SourceRef(
                        null, null, "trade_db", "orders", Set.of(), SourceType.CDC, null, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(), List.of(sub)));
        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);
        assertThat(matcher.match(cdcEvent("trade_db", "orders", CdcOp.INSERT, Map.of())))
                .isEmpty();
    }

    /**
     * DelayedEvent 按 subscriptionId 路由（ADR-0006 addendum）：fire 到点经 dispatcher.onEvent 回流，
     * matcher 在 Snapshot.subscriptionsById 直查唯一订阅，扇出其 pipelineIds。
     */
    @Test
    void matchesDelayedBySubscriptionId() {
        Pipeline p1 = pipeline(1L, 1);
        Pipeline p2 = pipeline(2L, 1);
        // sub.id = 42；故意把 sourceType 设为 CDC（突出"DelayedEvent 不靠 sourceType 路由，只看 subscriptionId"）。
        Subscription sub = new Subscription(
                42L,
                "smoke",
                List.of(1L, 2L),
                new Subscription.SourceRef(
                        "mq", "topic", "trade_db", "orders", Set.of(CdcOp.INSERT), SourceType.CDC, null, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, p1, 2L, p2), List.of(sub)));

        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);

        Event nested = cdcEvent("trade_db", "orders", CdcOp.INSERT, Map.of("amount", 1));
        DelayedEvent hit = new DelayedEvent(new DelayedMeta(42L, "order-1"), nested, Instant.now());
        DelayedEvent staleId = new DelayedEvent(new DelayedMeta(999L, "order-1"), nested, Instant.now());

        List<SubscriptionMatcher.MatchedSubscription> matched = matcher.match(hit);
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).subscription().id()).isEqualTo(42L);
        assertThat(matched.get(0).pipelines()).containsExactly(p1, p2);

        // subscriptionId 不在 registry → 空匹配（不路由到任何订阅）。
        assertThat(matcher.match(staleId)).isEmpty();
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

    private Event cdcEvent(final String db, final String table, final CdcOp op, final Map<String, Object> after) {
        CdcMeta meta = new CdcMeta("ibm-mq", db, table, Map.of());
        return new CdcEvent(meta, Map.of(), after, op, Instant.now());
    }
}
