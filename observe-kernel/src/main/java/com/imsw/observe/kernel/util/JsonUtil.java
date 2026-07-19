package com.imsw.observe.kernel.util;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 反序列化容忍未知属性——schema 演进时老 JSON 多余 key 静默忽略（如 ADR-0004 移除的
            // team/application 在老 definitionJson 里仍存在），符合"反序列化宽松、序列化精确"的稳健默认。
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonUtil() {}

    public static String toJson(final Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to serialize " + value.getClass().getSimpleName() + " to JSON", e);
        }
    }

    public static <T> T fromJson(final String json, final Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize JSON to " + type.getSimpleName() + ": " + json, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
