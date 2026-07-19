package com.imsw.observe.pipeline.infrastructure.subscription;

import java.util.ArrayList;
import java.util.List;

import com.imsw.observe.kernel.event.model.ApiEvent;
import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.DelayedEvent;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.event.model.TickEvent;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.SubscriptionMatcher;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

/**
 * 订阅匹配器（按 Event 子类型分发，ADR-0006 §4）。
 *
 * <p>{@link #match(Event)} 用 pattern match 按 {@link Event} 子类型选索引：
 * <ul>
 *   <li>{@link CdcEvent} → Snapshot 的 CDC（db/table）索引；额外校验 sourceType==CDC、opTypes 含 {@code cdc.op()}、fieldFilter。</li>
 *   <li>{@link TickEvent} → Snapshot 的 source 索引（cron name）；校验 sourceType==CRON。非 CDC 子类型不跑 opTypes/fieldFilter
 *       （fieldFilter 主要面向 CDC 的 before/after 路径；Cron/Api 订阅语义靠 sourceType + source 名硬匹配）。</li>
 *   <li>{@link ApiEvent} → Snapshot 的 source 索引（api name）；校验 sourceType==API。</li>
 *   <li>{@link DelayedEvent} → Snapshot 的 subscriptionsById 索引（按 {@link DelayedMeta#subscriptionId()}
 *       直查回原订阅）——fire 到点经 {@code SourceDispatcher.onEvent} 回流，matcher 按 subscriptionId 路由后扇出
 *       N 个 pipeline（旧"绕过 matcher"语义已被取代，见 ADR-0006 addendum）。</li>
 * </ul>
 */
public final class DefaultSubscriptionMatcher implements SubscriptionMatcher {

    private final PipelineRegistry registry;

    public DefaultSubscriptionMatcher(final PipelineRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<MatchedSubscription> match(final Event event) {
        List<MatchedSubscription> matched = new ArrayList<>();
        if (!registry.isLoaded() || event == null) {
            return matched;
        }
        PipelineRegistry.Snapshot snapshot = registry.snapshot();
        for (Subscription sub : snapshot.subscriptionsFor(event)) {
            List<Pipeline> pipelines = tryMatch(sub, event, snapshot);
            if (!pipelines.isEmpty()) {
                matched.add(new MatchedSubscription(sub, pipelines));
            }
        }
        return matched;
    }

    private static List<Pipeline> tryMatch(
            final Subscription sub, final Event event, final PipelineRegistry.Snapshot snapshot) {
        if (!matchesSource(sub, event)) {
            return List.of();
        }
        if (!passesFieldFilter(sub, event)) {
            return List.of();
        }
        // 扇出：遍历 pipelineIds，过滤掉 null（pipeline 不存在/未发布）。版本不再校验——跟 currentVersion。
        List<Pipeline> result = new ArrayList<>();
        for (Long id : sub.pipelineIds()) {
            Pipeline pipeline = snapshot.pipelineById(id);
            if (pipeline != null) {
                result.add(pipeline);
            }
        }
        return result;
    }

    private static boolean passesFieldFilter(final Subscription sub, final Event event) {
        // fieldFilter 仅对 CDC 有意义（before/after 路径）；Tick/Api/Delayed 子类型无对应路径解析，跳过过滤。
        if (!(event instanceof CdcEvent)) {
            return true;
        }
        return sub.fieldFilter() == null || sub.fieldFilter().matches(event);
    }

    @Override
    public boolean isLoaded() {
        return registry.isLoaded();
    }

    private static boolean matchesSource(final Subscription sub, final Event event) {
        // DelayedEvent：snapshot.subscriptionsFor 已按 subscriptionId 路由到唯一订阅（不依赖 source 字段）。
        // 直接放行，让 tryMatch 走 pipeline 扇出。
        if (event instanceof DelayedEvent) {
            return true;
        }
        if (sub.source() == null) {
            return false;
        }
        if (event instanceof CdcEvent cdc) {
            return matchesCdc(sub, cdc);
        }
        if (event instanceof TickEvent tick) {
            return matchesNamed(sub, SourceType.CRON, tick.meta().source());
        }
        if (event instanceof ApiEvent api) {
            return matchesNamed(sub, SourceType.API, api.meta().source());
        }
        return false;
    }

    private static boolean matchesCdc(final Subscription sub, final CdcEvent cdc) {
        SourceType type = sub.source().sourceType();
        if (type != null && type != SourceType.CDC) {
            return false;
        }
        // opTypes 仅 CDC 订阅生效（CdcOp），空集 = 不限 op。
        return sub.source().opTypes() == null
                || sub.source().opTypes().isEmpty()
                || sub.source().opTypes().contains(cdc.op());
    }

    /**
     * Cron/Api 订阅按 sourceType + source name 硬匹配。
     *
     * <p>订阅的 source name 存在 SourceRef.mq 槽（见 {@link PipelineRegistry.Snapshot} 注释）。
     */
    private static boolean matchesNamed(
            final Subscription sub, final SourceType expected, final String eventSourceName) {
        SourceType type = sub.source().sourceType();
        if (type != null && type != expected) {
            return false;
        }
        String subSourceName = sub.source().mq();
        return subSourceName != null && !subSourceName.isBlank() && subSourceName.equals(eventSourceName);
    }
}
