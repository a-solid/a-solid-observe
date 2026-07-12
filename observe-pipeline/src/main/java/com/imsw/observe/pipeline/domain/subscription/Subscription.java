package com.imsw.observe.pipeline.domain.subscription;

import java.util.Set;

import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;

public record Subscription(
        String id, String pipelineId, int pipelineVersion, SourceRef source, Condition fieldFilter, Action action) {

    public record SourceRef(String mq, String topic, String db, String table, Set<Op> opTypes, SourceType sourceType) {}
}
