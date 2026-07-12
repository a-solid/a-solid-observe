package com.imsw.observe.kernel.util;

import java.util.Collections;
import java.util.Map;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Converter
public class MapStringObjectToJsonConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = JsonUtil.mapper();

    private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(final Map<String, Object> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Map<String,Object> to JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(final String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize JSON to Map<String,Object>: " + dbData, e);
        }
    }
}
