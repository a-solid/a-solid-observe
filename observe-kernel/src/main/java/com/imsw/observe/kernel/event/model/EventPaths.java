package com.imsw.observe.kernel.event.model;

import java.util.Map;

/**
 * 事件字段路径解析（适配 sealed Event，ADR-0006）。
 *
 * <p>按 Event 子类型分发：
 * <ul>
 *   <li>{@link CdcEvent}：{@code op} / {@code before.<x>} / {@code after.<x>} /
 *       {@code meta.db} / {@code meta.table} / {@code meta.source} / {@code meta.attributes.<x>}</li>
 *   <li>{@link ApiEvent}：{@code payload.<x>} / {@code meta.apiName} /
 *       {@code meta.source} / {@code meta.attributes.<x>}</li>
 *   <li>{@link TickEvent}：{@code meta.cronName} / {@code meta.cronExpression} /
 *       {@code meta.source} / {@code meta.attributes.<x>}</li>
 *   <li>{@link DelayedEvent}：{@code meta.subscriptionId} / {@code meta.correlationKey}
 *       （原事件字段请由调用方在包装前从 originalEvent 提取）</li>
 * </ul>
 *
 * <p>兼容写法：路径若以 {@code event.} 前缀开头会先剥除，等价于不带前缀。
 */
public final class EventPaths {

    private EventPaths() {}

    public static Object get(final Event event, final String path) {
        if (event == null || path == null || path.isEmpty()) {
            return null;
        }
        String stripped = path.startsWith("event.") ? path.substring("event.".length()) : path;
        // sealed Event permits CdcEvent / TickEvent / ApiEvent / DelayedEvent
        if (event instanceof CdcEvent cdc) {
            return resolveCdc(cdc, stripped);
        }
        if (event instanceof ApiEvent api) {
            return resolveApi(api, stripped);
        }
        if (event instanceof TickEvent tick) {
            return resolveTick(tick, stripped);
        }
        if (event instanceof DelayedEvent delayed) {
            return resolveDelayed(delayed, stripped);
        }
        return null;
    }

    private static Object resolveCdc(final CdcEvent event, final String path) {
        if ("op".equals(path)) {
            return event.op() == null ? null : event.op().name();
        }
        if (path.startsWith("before.")) {
            return event.before() == null ? null : event.before().get(path.substring("before.".length()));
        }
        if (path.startsWith("after.")) {
            return event.after() == null ? null : event.after().get(path.substring("after.".length()));
        }
        if (path.startsWith("meta.")) {
            return resolveCdcMeta(event.meta(), path.substring("meta.".length()));
        }
        return null;
    }

    private static Object resolveCdcMeta(final CdcMeta meta, final String rest) {
        if (meta == null) {
            return null;
        }
        if ("db".equals(rest)) {
            return meta.db();
        }
        if ("table".equals(rest)) {
            return meta.table();
        }
        if ("source".equals(rest)) {
            return meta.source();
        }
        return resolveAttributes(meta.attributes(), rest);
    }

    private static Object resolveApi(final ApiEvent event, final String path) {
        if (path.startsWith("payload.")) {
            return event.payload() == null ? null : event.payload().get(path.substring("payload.".length()));
        }
        if (path.startsWith("meta.")) {
            return resolveApiMeta(event.meta(), path.substring("meta.".length()));
        }
        return null;
    }

    private static Object resolveApiMeta(final ApiMeta meta, final String rest) {
        if (meta == null) {
            return null;
        }
        if ("apiName".equals(rest)) {
            return meta.apiName();
        }
        if ("source".equals(rest)) {
            return meta.source();
        }
        return resolveAttributes(meta.attributes(), rest);
    }

    private static Object resolveTick(final TickEvent event, final String path) {
        if (path.startsWith("meta.")) {
            return resolveTickMeta(event.meta(), path.substring("meta.".length()));
        }
        return null;
    }

    private static Object resolveTickMeta(final TickMeta meta, final String rest) {
        if (meta == null) {
            return null;
        }
        if ("cronName".equals(rest)) {
            return meta.cronName();
        }
        if ("cronExpression".equals(rest)) {
            return meta.cronExpression();
        }
        if ("source".equals(rest)) {
            return meta.source();
        }
        return resolveAttributes(meta.attributes(), rest);
    }

    private static Object resolveDelayed(final DelayedEvent event, final String path) {
        if (path.startsWith("meta.")) {
            return resolveDelayedMeta(event.meta(), path.substring("meta.".length()));
        }
        return null;
    }

    private static Object resolveDelayedMeta(final DelayedMeta meta, final String rest) {
        if (meta == null) {
            return null;
        }
        if ("subscriptionId".equals(rest)) {
            return meta.subscriptionId();
        }
        if ("correlationKey".equals(rest)) {
            return meta.correlationKey();
        }
        return null;
    }

    private static Object resolveAttributes(final Map<String, Object> attrs, final String rest) {
        if (attrs == null) {
            return null;
        }
        if (rest.startsWith("attributes.")) {
            return attrs.get(rest.substring("attributes.".length()));
        }
        return null;
    }
}
