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
         * <p>仅收 {@code sourceType == CDC} 且 db/table 非空的订阅。CDC 是唯一走"内容路由"的事件
         * （CDC 消息无订阅信息，按 db/table 匹配，可能一对多）。
         */
        final Map<String, List<Subscription>> subscriptionsByDbTable;

        /**
         * 全订阅索引 by id：key = {@code subscription.id()}。每条订阅无条件入索引（不依赖 sourceType）。
         *
         * <p>三种事件类型共用此索引：TickEvent / ApiEvent / DelayedEvent（它们的 meta 都带 subscriptionId 一等字段，
         * 见 ADR-0007 addendum）。matcher 按此路由——直查唯一订阅，与 CDC 的"内容路由"形成对称。
         */
        final Map<Long, Subscription> subscriptionsById;

        /**
         * (namespace, name) → Subscription 索引：仅 ApiSource HTTP 入口用（按 path 参数 (ns,name) 查订阅得 id，
         * wrap ApiEvent）。matcher 不用——matcher 拿到的 ApiEvent 已带 subscriptionId（HTTP 入口填好）。
         */
        final Map<String, Subscription> subscriptionsByNamespaceAndName;

        /**
         * (namespace, name) → Pipeline 索引：仅 InjectController 用（按业务键寻址 pipeline 替代物理 id）。
         * 与其它 controller 对齐——CONTEXT.md "对外 API 用业务键寻址" 铁律。
         */
        final Map<String, Pipeline> pipelinesByNamespaceAndName;

        final boolean loaded;

        private Snapshot(
                final Map<Long, Pipeline> pipelinesById,
                final Map<String, List<Subscription>> subscriptionsByDbTable,
                final Map<Long, Subscription> subscriptionsById,
                final Map<String, Subscription> subscriptionsByNamespaceAndName,
                final Map<String, Pipeline> pipelinesByNamespaceAndName,
                final boolean loaded) {
            this.pipelinesById = pipelinesById;
            this.subscriptionsByDbTable = subscriptionsByDbTable;
            this.subscriptionsById = subscriptionsById;
            this.subscriptionsByNamespaceAndName = subscriptionsByNamespaceAndName;
            this.pipelinesByNamespaceAndName = pipelinesByNamespaceAndName;
            this.loaded = loaded;
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), false);
        }

        /**
         * 构建 loaded 快照，同时填充三个索引：CDC（db/table）、byId（全订阅）、byNamespaceAndName（API HTTP 入口用）。
         *
         * <p>每条订阅无条件入 byId 与 byNamespaceAndName（key 用 (ns,name) 拼装）；按 sourceType 决定是否入 CDC 索引：
         * <ul>
         *   <li>{@code CDC}：按 {@link Subscription.SourceRef#db()} / {@link Subscription.SourceRef#table()}
         *       入 subscriptionsByDbTable（同 key 可多条）。</li>
         * </ul>
         */
        public static Snapshot loaded(final Map<Long, Pipeline> pipelines, final List<Subscription> subscriptions) {
            Map<Long, Pipeline> pipelineCopy = Map.copyOf(pipelines);
            Map<String, Pipeline> pipelineByNsName = new HashMap<>();
            for (Pipeline p : pipelineCopy.values()) {
                if (p.namespace() != null && p.name() != null) {
                    pipelineByNsName.put(nsNameKey(p.namespace(), p.name()), p);
                }
            }
            Map<String, List<Subscription>> cdcIndex = new HashMap<>();
            Map<Long, Subscription> byId = new HashMap<>();
            Map<String, Subscription> byNsName = new HashMap<>();
            for (Subscription sub : subscriptions) {
                if (sub.id() != null) {
                    byId.put(sub.id(), sub);
                }
                // byNamespaceAndName：所有订阅都入——HTTP 入口可能 hit 任意 (ns,name) 组合（不止 API）。
                // 真正"是否接受 ApiEvent"由 matcher 的 sourceType 校验决定。
                if (sub.namespace() != null && sub.name() != null) {
                    byNsName.put(nsNameKey(sub.namespace(), sub.name()), sub);
                }
                if (sub.source() == null) {
                    continue;
                }
                SourceType type = sub.source().sourceType();
                if (type == SourceType.CDC) {
                    String key = dbTableKey(sub.source().db(), sub.source().table());
                    cdcIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(sub);
                }
            }
            Map<String, List<Subscription>> immutableCdc = freeze(cdcIndex);
            return new Snapshot(
                    Collections.unmodifiableMap(pipelineCopy),
                    Collections.unmodifiableMap(immutableCdc),
                    Collections.unmodifiableMap(byId),
                    Collections.unmodifiableMap(byNsName),
                    Collections.unmodifiableMap(pipelineByNsName),
                    true);
        }

        private static Map<String, List<Subscription>> freeze(final Map<String, List<Subscription>> raw) {
            Map<String, List<Subscription>> immutable = new HashMap<>();
            raw.forEach((k, v) -> immutable.put(k, Collections.unmodifiableList(v)));
            return immutable;
        }

        /**
         * 按 Event 子类型分发到对应索引（ADR-0006 §3.3 / §4 + ADR-0007 addendum）。
         *
         * <ul>
         *   <li>{@link CdcEvent} → subscriptionsByDbTable（key = {@link CdcMeta#db()}|{@link CdcMeta#table()}）。
         *       内容路由——一条 CDC 可能匹配多个订阅。</li>
         *   <li>{@link TickEvent} / {@link ApiEvent} / {@link DelayedEvent} → subscriptionsById
         *       （key = {@code meta().subscriptionId()}）。三种事件全对称——直查唯一订阅。</li>
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
            Long subId = subscriptionIdOf(event);
            if (subId == null) {
                return List.of();
            }
            Subscription sub = subscriptionsById.get(subId);
            return sub == null ? List.of() : List.of(sub);
        }

        /** ApiSource HTTP 入口专用：按 (namespace, name) 直查订阅（找不到返回 null）。 */
        public Subscription subscriptionByNamespaceAndName(final String namespace, final String name) {
            if (namespace == null || name == null) {
                return null;
            }
            return subscriptionsByNamespaceAndName.get(nsNameKey(namespace, name));
        }

        /** Tick/Api/Delayed 三种事件的 subscriptionId 提取（meta 都带此字段）。 */
        private static Long subscriptionIdOf(final Event event) {
            if (event instanceof TickEvent tick) {
                return tick.meta().subscriptionId();
            }
            if (event instanceof ApiEvent api) {
                return api.meta().subscriptionId();
            }
            if (event instanceof DelayedEvent delayed) {
                return delayed.meta().subscriptionId();
            }
            return null;
        }

        public Pipeline pipelineById(final Long pipelineId) {
            return pipelinesById.get(pipelineId);
        }

        /** InjectController 专用：按 (namespace, name) 直查 pipeline（找不到返回 null）。 */
        public Pipeline pipelineByNamespaceAndName(final String namespace, final String name) {
            if (namespace == null || name == null) {
                return null;
            }
            return pipelinesByNamespaceAndName.get(nsNameKey(namespace, name));
        }

        static String dbTableKey(final String db, final String table) {
            return (db == null ? "" : db) + "|" + (table == null ? "" : table);
        }

        static String nsNameKey(final String namespace, final String name) {
            return namespace + "|" + name;
        }
    }
}
