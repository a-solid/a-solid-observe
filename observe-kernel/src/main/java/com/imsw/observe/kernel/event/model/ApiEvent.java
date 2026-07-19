package com.imsw.observe.kernel.event.model;

import java.time.Instant;
import java.util.Map;

/**
 * 外部 API 调用（如 HTTP POST body）触发的事件。由 {@code ApiSource} 产出。
 *
 * @param meta     {@link ApiMeta}（含 api name / attributes）
 * @param payload  HTTP body 反序列化后的 Map
 * @param sourceTs 接收时间
 */
public record ApiEvent(ApiMeta meta, Map<String, Object> payload, Instant sourceTs) implements Event {

    @Override
    public SourceType sourceType() {
        return SourceType.API;
    }
}
