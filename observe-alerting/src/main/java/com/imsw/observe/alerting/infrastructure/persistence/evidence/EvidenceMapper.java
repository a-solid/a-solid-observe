package com.imsw.observe.alerting.infrastructure.persistence.evidence;

import com.imsw.observe.alerting.domain.EvidenceEntity;

public final class EvidenceMapper {

    private EvidenceMapper() {}

    public static EvidenceEntity toEntity(final EvidencePo po) {
        if (po == null) {
            return null;
        }
        return new EvidenceEntity(
                po.id,
                po.alertId,
                po.namespace,
                po.pipelineId,
                po.pipelineVersion == null ? 0 : po.pipelineVersion,
                po.executionId,
                po.nodeName,
                po.triggerEvent,
                po.traceId,
                po.spanId,
                po.capturedAt,
                po.truncated != null && po.truncated,
                po.emitSeq == null ? 0 : po.emitSeq);
    }

    /**
     * 把 {@link EvidenceEntity} 映射成 PO（尚未落库，id/emitSeq 由 sink 填充）。
     *
     * <p>{@code sizeBytes} = triggerEvent JSON 的 UTF-8 字节数。
     */
    public static EvidencePo toPo(final EvidenceEntity entity) {
        if (entity == null) {
            return null;
        }
        EvidencePo po = new EvidencePo();
        po.id = entity.id();
        po.alertId = entity.alertId();
        po.namespace = entity.namespace();
        po.pipelineId = entity.pipelineId();
        po.pipelineVersion = entity.pipelineVersion();
        po.executionId = entity.executionId();
        po.nodeName = entity.nodeName();
        po.triggerEvent = entity.triggerEvent();
        po.traceId = entity.traceId();
        po.spanId = entity.spanId();
        po.capturedAt = entity.capturedAt();
        po.truncated = entity.truncated();
        po.sizeBytes = byteSize(entity.triggerEvent());
        po.emitSeq = entity.emitSeq();
        return po;
    }

    private static int byteSize(final String triggerEvent) {
        if (triggerEvent == null) {
            return 0;
        }
        return triggerEvent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
