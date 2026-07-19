package com.imsw.observe.alerting.infrastructure.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imsw.observe.alerting.domain.EvidenceEntity;
import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionMeta;

/**
 * Evidence 收集器（ADR-0005 §2 + simplify-contexts 重构）。
 *
 * <p>evidence 的内容载荷 = 触发事件快照（序列化 {@link ExecutionMeta#triggerEvent()}）——排错时最硬的
 * 证据（报警那一刻数据长啥样）。取代历史版本的 nodeOutputs/capture 机制（node 输出链零调用、
 * capture 从空 nodeOutputs 捞 key 恒空——均坏/dead，已砍）。
 *
 * <p>每个 evidence 行 = 触发事件 JSON + 元信息（namespace/pipeline/execution/trace/capturedAt/emitSeq）。
 * 序列化超 {@link #MAX_BYTES} 截断并标 truncated。
 */
public final class EvidenceCollector {

    private static final int MAX_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;

    public EvidenceCollector(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Collected collect(final AlertSignal signal, final ExecutionContext ctx) {
        ExecutionMeta meta = ctx.meta();
        Serialized serialized = serializeEvent(meta.triggerEvent());
        EvidenceEntity entity = new EvidenceEntity(
                null,
                null,
                meta.namespace(),
                meta.pipelineId(),
                meta.pipelineVersion(),
                meta.executionId(),
                null,
                serialized.json,
                meta.traceId(),
                meta.spanId(),
                java.time.Instant.now(),
                serialized.truncated,
                0);
        return new Collected(entity, serialized.sizeBytes, serialized.truncated);
    }

    private Serialized serializeEvent(final Object event) {
        if (event == null) {
            return new Serialized(null, 0, false);
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return new Serialized(null, 0, false);
        }
        int size = json.length();
        if (size <= MAX_BYTES) {
            return new Serialized(json, size, false);
        }
        // 截断到字节上限（粗粒度——按字符数截，JSON 可能不完整，但 evidence 仅排错展示用，可接受）。
        return new Serialized(json.substring(0, MAX_BYTES), MAX_BYTES, true);
    }

    private record Serialized(String json, int sizeBytes, boolean truncated) {}

    public record Collected(EvidenceEntity entity, int sizeBytes, boolean truncated) {}
}
