package com.imsw.observe.kernel.event.model;

import java.util.Map;

public final class EventPaths {

    private EventPaths() {}

    public static Object get(final Event event, final String path) {
        if (event == null || path == null || path.isEmpty()) {
            return null;
        }
        if ("event.op".equals(path) || "op".equals(path)) {
            return event.op() == null ? null : event.op().name();
        }
        String stripped = path.startsWith("event.") ? path.substring("event.".length()) : path;
        return resolveStripped(event, stripped);
    }

    private static Object resolveStripped(final Event event, final String stripped) {
        if (stripped.startsWith("before.")) {
            return event.before() == null ? null : event.before().get(stripped.substring("before.".length()));
        }
        if (stripped.startsWith("after.")) {
            return event.after() == null ? null : event.after().get(stripped.substring("after.".length()));
        }
        if (stripped.startsWith("meta.")) {
            return resolveMeta(event, stripped.substring("meta.".length()));
        }
        return null;
    }

    private static Object resolveMeta(final Event event, final String rest) {
        Event.EventMeta meta = event.meta();
        if (meta == null) {
            return null;
        }
        if (rest.equals("db")) {
            return meta.db();
        }
        if (rest.equals("table")) {
            return meta.table();
        }
        if (rest.equals("source")) {
            return meta.source();
        }
        if (rest.startsWith("attributes.")) {
            Map<String, Object> attrs = meta.attributes();
            return attrs == null ? null : attrs.get(rest.substring("attributes.".length()));
        }
        return null;
    }
}
