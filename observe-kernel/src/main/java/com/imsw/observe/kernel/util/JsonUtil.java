package com.imsw.observe.kernel.util;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
