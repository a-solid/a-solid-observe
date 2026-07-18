package com.imsw.observe.alerting.infrastructure.persistence.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imsw.observe.alerting.domain.EvidenceEntity;
import com.imsw.observe.kernel.util.JsonUtil;

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
                po.outputs,
                po.traceId,
                po.spanId,
                po.capturedAt,
                po.truncated != null && po.truncated,
                po.emitSeq == null ? 0 : po.emitSeq);
    }

    /**
     * 把 {@link EvidenceEntity} 映射成 PO（尚未落库，id/sizeBytes 由 sink 填充）。
     *
     * <p>{@code sizeBytes} = outputs 序列化后的真实字节数（B8 取值修正：原取 Map entry 数，名实不符）。
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
        po.outputs = entity.outputs();
        po.traceId = entity.traceId();
        po.spanId = entity.spanId();
        po.capturedAt = entity.capturedAt();
        po.truncated = entity.truncated();
        po.sizeBytes = serializedSize(entity.outputs());
        po.emitSeq = entity.emitSeq();
        return po;
    }

    private static int serializedSize(final java.util.Map<String, Object> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return 0;
        }
        ObjectMapper mapper = JsonUtil.mapper();
        try {
            return mapper.writeValueAsBytes(outputs).length;
        } catch (JsonProcessingException e) {
            // 序列化失败时回退为 entry 数（不应发生；outputs 来自受控来源）
            return outputs.size();
        }
    }
}
