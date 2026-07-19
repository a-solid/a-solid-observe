package com.imsw.observe.kernel.event.model;

import java.util.Map;

/**
 * {@link ApiEvent} 的元数据。sourceType 由子类型隐式 = API，故不再单独字段（ADR-0006）。
 *
 * <p>由 {@code ApiSource} HTTP 入口（{@code POST /api/v1/events/api/{namespace}/{name}}）产出：
 * 按 (namespace, name) 在 {@code Snapshot.subscriptionsByNamespaceAndName} 索引查得 subscriptionId 后填入。
 * matcher 按 subscriptionId 在 {@code Snapshot.subscriptionsById} 直查回原订阅（与 Tick/Delayed 对称，
 * 见 ADR-0007 addendum）。
 *
 * @param subscriptionId 原订阅 id（matcher 路由键）
 * @param attributes     附加属性（{@code http_method} / {@code http_path} / {@code received_at} 等）
 */
public record ApiMeta(Long subscriptionId, Map<String, Object> attributes) {}
