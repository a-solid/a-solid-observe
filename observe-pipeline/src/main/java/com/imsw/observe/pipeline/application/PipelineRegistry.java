package com.imsw.observe.pipeline.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public Pipeline pipeline(final Long id) {
        return snapshot.pipelinesById.get(id);
    }

    public static final class Snapshot {

        final Map<Long, Pipeline> pipelinesById;

        final Map<String, List<Subscription>> subscriptionsByDbTable;

        final boolean loaded;

        private Snapshot(
                final Map<Long, Pipeline> pipelinesById,
                final Map<String, List<Subscription>> subscriptionsByDbTable,
                final boolean loaded) {
            this.pipelinesById = pipelinesById;
            this.subscriptionsByDbTable = subscriptionsByDbTable;
            this.loaded = loaded;
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), false);
        }

        public static Snapshot loaded(final Map<Long, Pipeline> pipelines, final List<Subscription> subscriptions) {
            Map<Long, Pipeline> pipelineCopy = Map.copyOf(pipelines);
            Map<String, List<Subscription>> index = new HashMap<>();
            for (Subscription sub : subscriptions) {
                if (sub.source() == null) {
                    continue;
                }
                String key = dbTableKey(sub.source().db(), sub.source().table());
                index.computeIfAbsent(key, k -> new ArrayList<>()).add(sub);
            }
            Map<String, List<Subscription>> immutableIndex = new HashMap<>();
            index.forEach((k, v) -> immutableIndex.put(k, Collections.unmodifiableList(v)));
            return new Snapshot(
                    Collections.unmodifiableMap(pipelineCopy), Collections.unmodifiableMap(immutableIndex), true);
        }

        public List<Subscription> subscriptionsFor(final String db, final String table) {
            return subscriptionsByDbTable.getOrDefault(dbTableKey(db, table), List.of());
        }

        public Pipeline pipelineById(final Long pipelineId) {
            return pipelinesById.get(pipelineId);
        }

        public int subscriptionCount() {
            return subscriptionsByDbTable.values().stream().mapToInt(List::size).sum();
        }

        public static String dbTableKey(final String db, final String table) {
            return (db == null ? "" : db) + "|" + (table == null ? "" : table);
        }
    }
}
