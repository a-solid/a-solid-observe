package com.imsw.observe.config.domain;

import java.time.Duration;
import java.util.Set;

import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.subscription.Condition;

public record Subscription(
        String id,
        String pipelineId,
        int pipelineVersion,
        String mq,
        String topic,
        String db,
        String table,
        Set<Op> opTypes,
        SourceType sourceType,
        Condition fieldFilter,
        ActionType actionType,
        Duration scheduleDelay,
        String scheduleCorrelationKeyPath,
        String name,
        String description,
        Status status,
        String createdBy,
        java.time.Instant createdAt,
        java.time.Instant updatedAt) {

    public enum ActionType {
        RUN,
        SCHEDULE,
        CANCEL
    }

    public enum Status {
        ACTIVE,
        INACTIVE
    }
}
