package com.imsw.observe.pipeline.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.imsw.observe.kernel.event.model.ApiEvent;
import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.DelayedEvent;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.event.model.TickEvent;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

public final class PipelineRegistry {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PipelineRegistry.class);

    private volatile Snapshot snapshot = Snapshot.empty();

    /**
     * Snapshot 观察者（{@link SnapshotListener}）。{@code null} = 无 Spring 场景（测试直 new），
     * {@link #notifyListeners} 遇 null 跳过。用 {@link org.springframework.beans.factory.ObjectProvider}
     * 而非 {@code List} 是为破构造期循环：registry → CronSource(listener) → dispatcher → matcher → registry。
     * 构造期不解析，{@link #replace} 时 {@code orderedStream()} 取当前 listener bean（此时 bean 图已就绪）。
     */
    private final org.springframework.beans.factory.ObjectProvider<SnapshotListener> listeners;

    /** 无参构造器：测试 / 无 Spring 场景（无 listener，replace 不通知）。 */
    public PipelineRegistry() {
        this(null);
    }

    public PipelineRegistry(final org.springframework.beans.factory.ObjectProvider<SnapshotListener> listeners) {
        this.listeners = listeners;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void replace(final Snapshot next) {
        // swap 先发生——观察者在 onSnapshot 里看到的是已生效状态。
        Snapshot applied = next == null ? Snapshot.empty() : next;
        this.snapshot = applied;
        notifyListeners(applied);
    }

    private void notifyListeners(final Snapshot snap) {
        if (listeners == null) {
            return;
        }
        // per-listener 容错：单个 listener 抛异常不阻断 swap（已成事实）、不影响其他 listener。
        listeners.orderedStream().forEach(listener -> {
            try {
                listener.onSnapshot(snap);
            } catch (RuntimeException e) {
                LOG.error("snapshot listener failed; snapshot already applied", e);
            }
        });
    }

    public boolean isLoaded() {
        return snapshot.loaded;
    }

    public static final class Snapshot {

        final Map<Long, Pipeline> pipelinesById;

        /**
         * CDC 订阅索引：key = {@code db|table}（{@link #dbTableKey}）。
         *
         * <p>仅收 {@code sourceType == CDC} 且 db/table 非空的订阅。
         */
        final Map<String, List<Subscription>> subscriptionsByDbTable;

        /**
         * Cron/Api 订阅索引：key = source name（{@link Subscription.SourceRef#mq()} 复用为 source 名槽）。
         *
         * <p>仅收 {@code sourceType == CRON} 或 {@code sourceType == API} 且 source name 非空的订阅。
         * source name 存在 SourceRef.mq 字段（Cron/Api 不用 mq 队列语义，复用为 source 标识，
         * 与 {@link TickMeta#source()} / {@link ApiMeta#source()} 对齐——见 ADR-0006 §3.3）。
         */
        final Map<String, List<Subscription>> subscriptionsBySource;

        /**
         * DelayedEvent 订阅索引：key = {@code subscription.id()}。
         *
         * <p>每条订阅无条件入索引（sub.id 不依赖 sourceType）。DelayedEvent fire 到点经
         * {@code SourceDispatcher.onEvent} 回流 → matcher 路由 → 本索引按 subscriptionId 直查唯一订阅
         * （与 CdcMeta 的 db/table、TickMeta/ApiMeta 的 source 对称）。
         */
        final Map<Long, Subscription> subscriptionsById;

        final boolean loaded;

        private Snapshot(
                final Map<Long, Pipeline> pipelinesById,
                final Map<String, List<Subscription>> subscriptionsByDbTable,
                final Map<String, List<Subscription>> subscriptionsBySource,
                final Map<Long, Subscription> subscriptionsById,
                final boolean loaded) {
            this.pipelinesById = pipelinesById;
            this.subscriptionsByDbTable = subscriptionsByDbTable;
            this.subscriptionsBySource = subscriptionsBySource;
            this.subscriptionsById = subscriptionsById;
            this.loaded = loaded;
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), false);
        }

        /**
         * 构建 loaded 快照，同时填充 CDC（db/table）和 Cron/Api（source name）两个索引。
         *
         * <p>一条订阅按其 {@code sourceType} 路由到对应索引：
         * <ul>
         *   <li>{@code CDC}：按 {@link Subscription.SourceRef#db()} / {@link Subscription.SourceRef#table()}
         *       入 subscriptionsByDbTable（同 key 可多条）。</li>
         *   <li>{@code CRON} / {@code API}：按 {@link Subscription.SourceRef#mq()}（复用为 source name）
         *       入 subscriptionsBySource。</li>
         *   <li>{@code null} source 或无法定位 key：跳过。</li>
         * </ul>
         */
        public static Snapshot loaded(final Map<Long, Pipeline> pipelines, final List<Subscription> subscriptions) {
            Map<Long, Pipeline> pipelineCopy = Map.copyOf(pipelines);
            Map<String, List<Subscription>> cdcIndex = new HashMap<>();
            Map<String, List<Subscription>> sourceIndex = new HashMap<>();
            Map<Long, Subscription> byId = new HashMap<>();
            for (Subscription sub : subscriptions) {
                // subscriptionsById 无条件收——DelayedEvent fire 回流时按 subscriptionId 直查（不依赖 sourceType）。
                if (sub.id() != null) {
                    byId.put(sub.id(), sub);
                }
                if (sub.source() == null) {
                    continue;
                }
                SourceType type = sub.source().sourceType();
                if (type == SourceType.CDC) {
                    String key = dbTableKey(sub.source().db(), sub.source().table());
                    cdcIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(sub);
                } else if (type == SourceType.CRON || type == SourceType.API) {
                    String sourceName = sub.source().mq();
                    if (sourceName == null || sourceName.isBlank()) {
                        continue;
                    }
                    sourceIndex
                            .computeIfAbsent(sourceName, k -> new ArrayList<>())
                            .add(sub);
                }
            }
            Map<String, List<Subscription>> immutableCdc = freeze(cdcIndex);
            Map<String, List<Subscription>> immutableSource = freeze(sourceIndex);
            return new Snapshot(
                    Collections.unmodifiableMap(pipelineCopy),
                    Collections.unmodifiableMap(immutableCdc),
                    Collections.unmodifiableMap(immutableSource),
                    Collections.unmodifiableMap(byId),
                    true);
        }

        private static Map<String, List<Subscription>> freeze(final Map<String, List<Subscription>> raw) {
            Map<String, List<Subscription>> immutable = new HashMap<>();
            raw.forEach((k, v) -> immutable.put(k, Collections.unmodifiableList(v)));
            return immutable;
        }

        /**
         * 按 Event 子类型分发到对应索引（ADR-0006 §3.3 / §4）。
         *
         * <ul>
         *   <li>{@link CdcEvent} → subscriptionsByDbTable（key = {@link CdcMeta#db()}|{@link CdcMeta#table()}）。</li>
         *   <li>{@link TickEvent} / {@link ApiEvent} → subscriptionsBySource（key = {@code meta().source()}）。</li>
         *   <li>{@link DelayedEvent} → subscriptionsById（key = {@link DelayedMeta#subscriptionId()}）——
         *       fire 到点经 {@code SourceDispatcher.onEvent} 回流，按 subscriptionId 直查唯一订阅。
         *       历史"绕过 matcher"语义（ADR-0006 §9.2）已被取代，见 ADR-0006 addendum。</li>
         * </ul>
         */
        public List<Subscription> subscriptionsFor(final Event event) {
            // 按 Event 子类型选索引 + 抽 key。sealed Event 在类型层保证 sourceType() 穷尽
            // （每个子类型必须实现 Event.sourceType()）；此处消费者侧仍是 instanceof 级联——
            // Java 17 不支持 switch 模式，新增子类型需手动在此 + matcher.matchesSource 补分支。
            if (event instanceof CdcEvent cdc) {
                return subscriptionsByDbTable.getOrDefault(
                        dbTableKey(cdc.meta().db(), cdc.meta().table()), List.of());
            }
            if (event instanceof TickEvent tick) {
                return subscriptionsBySource.getOrDefault(tick.meta().source(), List.of());
            }
            if (event instanceof ApiEvent api) {
                return subscriptionsBySource.getOrDefault(api.meta().source(), List.of());
            }
            if (event instanceof DelayedEvent delayed) {
                Subscription sub = subscriptionsById.get(delayed.meta().subscriptionId());
                return sub == null ? List.of() : List.of(sub);
            }
            return List.of();
        }

        public Pipeline pipelineById(final Long pipelineId) {
            return pipelinesById.get(pipelineId);
        }

        static String dbTableKey(final String db, final String table) {
            return (db == null ? "" : db) + "|" + (table == null ? "" : table);
        }
    }
}
