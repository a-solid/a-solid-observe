package com.imsw.observe.pipeline.domain.subscription;

import java.util.Set;

import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.SourceType;

public record Subscription(
        Long id,
        String namespace,
        java.util.List<Long> pipelineIds,
        SourceRef source,
        Condition fieldFilter,
        Action action) {

    /**
     * 订阅源引用。
     *
     * <p>{@code opTypes} 仅对 CDC 订阅有意义（{@link CdcOp} INSERT/UPDATE/DELETE）；
     * Cron/Api 订阅不配 opTypes（null 或空），matcher 跳过 opTypes 校验（ADR-0006 §2/§4）。
     *
     * <p>Cron per-subscription scheduling (B4, ADR-0007)：{@code cronExpression}/{@code cronName}/
     * {@code concurrent} 仅对 sourceType == CRON 的订阅有意义（其它类型保持 null）。
     */
    public record SourceRef(
            String mq,
            String topic,
            String db,
            String table,
            Set<CdcOp> opTypes,
            SourceType sourceType,
            String cronExpression,
            String cronName,
            Concurrent concurrent) {

        /**
         * Cron 订阅并发策略（B4）。{@link #SKIP}：上一轮未完成则跳过（默认）；{@link #ALLOW}：允许重叠。
         */
        public enum Concurrent {
            SKIP,
            ALLOW
        }
    }
}
