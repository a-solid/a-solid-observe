package com.imsw.observe.config.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import com.imsw.observe.config.domain.SubscriptionDefinition;
import com.imsw.observe.config.infrastructure.ConditionCodec;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.SourceType;

public final class SubscriptionMapper {

    private SubscriptionMapper() {}

    public static SubscriptionDefinition toEntity(final SubscriptionPo po, final ConditionCodec codec) {
        if (po == null) {
            return null;
        }
        return new SubscriptionDefinition(
                po.id,
                po.namespace,
                po.pipelineIds,
                po.mq,
                po.topic,
                po.db,
                po.tableName,
                toOpSet(po.opTypes),
                toSourceType(po.sourceType),
                codec.fromJson(po.fieldFilter),
                toActionType(po.actionType),
                toDuration(po.scheduleDelayMs),
                po.scheduleCorrelationKeyPath,
                po.name,
                po.description,
                toStatus(po.status),
                po.createdBy,
                po.createdAt,
                po.updatedAt,
                po.cronExpression,
                po.cronName,
                toConcurrent(po.concurrent));
    }

    public static SubscriptionPo toPo(final SubscriptionDefinition entity, final ConditionCodec codec) {
        if (entity == null) {
            return null;
        }
        SubscriptionPo po = new SubscriptionPo();
        po.id = entity.id();
        po.namespace = entity.namespace();
        po.pipelineIds = entity.pipelineIds() == null ? new ArrayList<>() : new ArrayList<>(entity.pipelineIds());
        po.mq = entity.mq();
        po.topic = entity.topic();
        po.db = entity.db();
        po.tableName = entity.table();
        po.opTypes = fromOpSet(entity.opTypes());
        po.sourceType = fromSourceType(entity.sourceType());
        po.fieldFilter = codec.toJson(entity.fieldFilter());
        po.actionType = fromActionType(entity.actionType());
        po.scheduleDelayMs = fromDuration(entity.scheduleDelay());
        po.scheduleCorrelationKeyPath = entity.scheduleCorrelationKeyPath();
        po.cronExpression = entity.cronExpression();
        po.cronName = entity.cronName();
        po.concurrent = fromConcurrent(entity.concurrent());
        po.name = entity.name();
        po.description = entity.description();
        po.status = fromStatus(entity.status());
        po.createdBy = entity.createdBy();
        po.createdAt = nullSafeNow(entity.createdAt());
        po.updatedAt = nullSafeNow(entity.updatedAt());
        return po;
    }

    private static Instant nullSafeNow(final Instant value) {
        return value == null ? Instant.now() : value;
    }

    private static Set<CdcOp> toOpSet(final Set<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        return raw.stream().map(CdcOp::valueOf).collect(Collectors.toSet());
    }

    private static Set<String> fromOpSet(final Set<CdcOp> ops) {
        if (ops == null) {
            return null;
        }
        return ops.stream().map(CdcOp::name).collect(Collectors.toSet());
    }

    private static SourceType toSourceType(final String raw) {
        return raw == null ? null : SourceType.valueOf(raw);
    }

    private static String fromSourceType(final SourceType type) {
        return type == null ? null : type.name();
    }

    private static SubscriptionDefinition.ActionType toActionType(final String raw) {
        return raw == null ? null : SubscriptionDefinition.ActionType.valueOf(raw);
    }

    private static String fromActionType(final SubscriptionDefinition.ActionType type) {
        return type == null ? "RUN" : type.name();
    }

    private static SubscriptionDefinition.Status toStatus(final String raw) {
        return raw == null ? null : SubscriptionDefinition.Status.valueOf(raw);
    }

    private static String fromStatus(final SubscriptionDefinition.Status status) {
        return status == null ? "ACTIVE" : status.name();
    }

    private static Duration toDuration(final Long millis) {
        return millis == null ? null : Duration.ofMillis(millis);
    }

    private static Long fromDuration(final Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    private static SubscriptionDefinition.Concurrent toConcurrent(final String raw) {
        return raw == null ? null : SubscriptionDefinition.Concurrent.valueOf(raw);
    }

    private static String fromConcurrent(final SubscriptionDefinition.Concurrent concurrent) {
        return concurrent == null ? null : concurrent.name();
    }
}
