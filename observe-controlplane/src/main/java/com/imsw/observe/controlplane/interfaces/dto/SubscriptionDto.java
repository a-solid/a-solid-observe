package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Duration;
import java.util.Set;

import com.imsw.observe.config.domain.Subscription;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.subscription.Condition;

public record SubscriptionDto(
        Long id,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        String mq,
        String topic,
        String db,
        String table,
        Set<Op> opTypes,
        SourceType sourceType,
        Condition fieldFilter,
        String actionType,
        Long scheduleDelayMs,
        String scheduleCorrelationKeyPath,
        String name,
        String description,
        String status) {

    public static SubscriptionDto from(final Subscription s) {
        Duration delay = s.scheduleDelay();
        return new SubscriptionDto(
                s.id(),
                s.namespace(),
                s.pipelineId(),
                s.pipelineVersion(),
                s.mq(),
                s.topic(),
                s.db(),
                s.table(),
                s.opTypes(),
                s.sourceType(),
                s.fieldFilter(),
                s.actionType() == null ? null : s.actionType().name(),
                delay == null ? null : delay.toMillis(),
                s.scheduleCorrelationKeyPath(),
                s.name(),
                s.description(),
                s.status() == null ? null : s.status().name());
    }
}
