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

    private volatile Snapshot snapshot = Snapshot.empty();

    public Snapshot snapshot() {
        return snapshot;
    }

    public void replace(final Snapshot next) {
        this.snapshot = next == null ? Snapshot.empty() : next;
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

        final boolean loaded;

        private Snapshot(
                final Map<Long, Pipeline> pipelinesById,
                final Map<String, List<Subscription>> subscriptionsByDbTable,
                final Map<String, List<Subscription>> subscriptionsBySource,
                final boolean loaded) {
            this.pipelinesById = pipelinesById;
            this.subscriptionsByDbTable = subscriptionsByDbTable;
            this.subscriptionsBySource = subscriptionsBySource;
            this.loaded = loaded;
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), false);
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
            for (Subscription sub : subscriptions) {
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
         *   <li>{@link DelayedEvent} → 空（DelayedEvent 绕过 matcher，见 ADR-0006 §9.2 / §4）。</li>
         * </ul>
         */
        public List<Subscription> subscriptionsFor(final Event event) {
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
            // DelayedEvent 不走 matcher（绕过 SourceDispatcher，直调 PipelineRunner）。
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
