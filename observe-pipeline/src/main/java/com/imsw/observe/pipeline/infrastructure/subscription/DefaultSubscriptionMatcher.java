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
 * 订阅匹配器（按 Event 子类型分发，ADR-0006 §4 + ADR-0007 addendum）。
 *
 * <p>{@link #match(Event)} 走两类索引：
 * <ul>
 *   <li>{@link CdcEvent} → subscriptionsByDbTable（内容路由，一对多）。额外校验 sourceType==CDC、
 *       opTypes 含 {@code cdc.op()}、fieldFilter。</li>
 *   <li>{@link TickEvent} / {@link ApiEvent} / {@link DelayedEvent} → subscriptionsById（按
 *       {@code meta().subscriptionId()} 直查唯一订阅）。三种事件全对称——Snapshot 已路由到唯一订阅，
 *       matcher 不再校验 source/opTypes（这些事件的路由键是 subscriptionId，不再走"source 名匹配"）。
 *       fieldFilter 对非 CDC 事件返回 true（fieldFilter 主要面向 CDC 的 before/after 路径）。</li>
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
        // Tick/Api/Delayed：snapshot 已按 subscriptionId 路由到唯一订阅，无需再校验 source/opTypes。
        // 消费者侧 instanceof 级联（Java 17 无 switch 模式，新增子类型需手动在此 + registry.subscriptionsFor 补分支）。
        if (event instanceof TickEvent || event instanceof ApiEvent || event instanceof DelayedEvent) {
            return true;
        }
        if (sub.source() == null) {
            return false;
        }
        if (event instanceof CdcEvent cdc) {
            return matchesCdc(sub, cdc);
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
}
