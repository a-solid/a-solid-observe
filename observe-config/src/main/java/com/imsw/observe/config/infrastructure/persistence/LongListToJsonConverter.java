package com.imsw.observe.config.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.imsw.observe.kernel.util.JsonUtil;

/**
 * {@code List<Long>} ↔ JSON 数组字符串（如 {@code [1001,1002]}）。
 *
 * <p>用于 {@code subscriptions.pipeline_ids} 列（一个 subscription 绑多 pipeline）。
 * null/empty 列 → 空 list（实体侧不持有 null）；实体侧空 list → null 列。
 */
@Converter
public class LongListToJsonConverter implements AttributeConverter<List<Long>, String> {

    @Override
    public String convertToDatabaseColumn(final List<Long> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return JsonUtil.toJson(attribute);
    }

    @Override
    public List<Long> convertToEntityAttribute(final String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        Long[] arr = JsonUtil.fromJson(dbData, Long[].class);
        List<Long> result = new ArrayList<>();
        if (arr != null) {
            for (Long v : arr) {
                if (v != null) {
                    result.add(v);
                }
            }
        }
        return result;
    }
}
