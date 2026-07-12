package com.imsw.observe.kernel.event.model;

import java.time.Instant;
import java.util.Map;

public record Event(EventMeta meta, Map<String, Object> before, Map<String, Object> after, Op op, Instant sourceTs) {

    public record EventMeta(
            SourceType sourceType, String source, String db, String table, Map<String, Object> attributes) {}
}
