package com.imsw.observe.kernel.event.model;

import java.time.Instant;
import java.util.Map;

/**
 * 执行上下文元数据（B9 / ADR-0004）：team/application/pipelineLabels 一等字段移除，
 * 维度统一为 {@code labels}（pipeline 的 labels 副本）。告警写入时由 sink 投影到 label_team/label_app/label_line。
 */
public record ExecutionMeta(
        Long executionId,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        Map<String, String> labels,
        String traceId,
        String spanId,
        SourceType triggerType,
        Event triggerEvent,
        Instant triggeredAt,
        Long subscriptionId) {}
