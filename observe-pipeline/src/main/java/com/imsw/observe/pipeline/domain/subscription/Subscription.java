package com.imsw.observe.pipeline.domain.subscription;

import java.util.Set;

import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.SourceType;

public record Subscription(
        Long id,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        SourceRef source,
        Condition fieldFilter,
        Action action) {

    /**
     * 订阅源引用。
     *
     * <p>{@code opTypes} 仅对 CDC 订阅有意义（{@link CdcOp} INSERT/UPDATE/DELETE）；
     * Cron/Api 订阅不配 opTypes（null 或空），matcher 跳过 opTypes 校验（ADR-0006 §2/§4）。
     */
    public record SourceRef(
            String mq, String topic, String db, String table, Set<CdcOp> opTypes, SourceType sourceType) {}
}
