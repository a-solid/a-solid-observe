package com.imsw.observe.config.infrastructure.persistence;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class StringSetToCsvConverter implements AttributeConverter<Set<String>, String> {

    @Override
    public String convertToDatabaseColumn(final Set<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return String.join(",", attribute);
    }

    @Override
    public Set<String> convertToEntityAttribute(final String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(dbData.split(",")).map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
