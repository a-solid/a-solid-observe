package com.imsw.observe.alerting.infrastructure.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

import com.imsw.observe.kernel.event.model.ExecutionContext;

public final class AnnotationRenderer {

    public Map<String, String> render(final Map<String, Object> annotations, final ExecutionContext ctx) {
        if (annotations == null || annotations.isEmpty()) {
            return Map.of();
        }
        Map<String, String> rendered = new LinkedHashMap<>();
        annotations.forEach((k, v) -> rendered.put(k, stringify(v)));
        return rendered;
    }

    private static String stringify(final Object value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }
}
