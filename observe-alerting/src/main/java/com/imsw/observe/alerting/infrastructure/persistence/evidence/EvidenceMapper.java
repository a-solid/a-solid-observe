package com.imsw.observe.alerting.infrastructure.persistence.evidence;

import com.imsw.observe.alerting.domain.EvidenceEntity;

public final class EvidenceMapper {

    private EvidenceMapper() {}

    public static EvidenceEntity toEntity(final EvidencePo po) {
        if (po == null) {
            return null;
        }
        return new EvidenceEntity(
                po.alertId,
                po.pipelineId,
                po.pipelineVersion == null ? 0 : po.pipelineVersion,
                po.executionId,
                po.nodeName,
                po.outputs,
                po.traceId,
                po.spanId,
                po.capturedAt,
                po.truncated != null && po.truncated);
    }

    public static EvidencePo toPo(final EvidenceEntity entity) {
        if (entity == null) {
            return null;
        }
        EvidencePo po = new EvidencePo();
        po.alertId = entity.alertId();
        po.pipelineId = entity.pipelineId();
        po.pipelineVersion = entity.pipelineVersion();
        po.executionId = entity.executionId();
        po.nodeName = entity.nodeName();
        po.outputs = entity.outputs();
        po.traceId = entity.traceId();
        po.spanId = entity.spanId();
        po.capturedAt = entity.capturedAt();
        po.truncated = entity.truncated();
        po.sizeBytes = entity.outputs() == null ? 0 : entity.outputs().size();
        return po;
    }
}
