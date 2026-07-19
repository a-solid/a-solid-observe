package com.imsw.observe.config.domain;

import java.time.Duration;
import java.util.Set;

import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.subscription.Condition;

public record SubscriptionDefinition(
        Long id,
        String namespace,
        java.util.List<Long> pipelineIds,
        String db,
        String table,
        Set<CdcOp> opTypes,
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
        java.time.Instant updatedAt,
        // Cron per-subscription scheduling (B4, ADR-0007 + addendum)。
        // 仅 sourceType == CRON 时有意义：cronExpression 必填且必须可被 Spring CronExpression 解析；
        // concurrent 控制重叠触发策略，null → SKIP。Cron/Api 路由改用订阅自己的 (namespace, name) 或
        // subscriptionId——不再需要 mq/cronName 字段（见 ADR-0007 addendum）。
        String cronExpression,
        Concurrent concurrent) {

    public enum ActionType {
        RUN,
        SCHEDULE,
        CANCEL
    }

    public enum Status {
        ACTIVE,
        INACTIVE
    }

    /**
     * Cron 订阅并发策略（B4）。
     *
     * <ul>
     *   <li>{@link #SKIP}：上一轮触发尚未完成时跳过本次（默认）。</li>
     *   <li>{@link #ALLOW}：允许重叠触发（调用方需自行保证线程安全）。</li>
     * </ul>
     */
    public enum Concurrent {
        SKIP,
        ALLOW
    }
}
