package com.imsw.observe.alerting.infrastructure.evidence;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把脚本 annotations（Object 值）字符串化为 alert 表的 String 值。 */
public final class AnnotationRenderer {

    public Map<String, String> render(final Map<String, Object> annotations) {
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
