package com.imsw.observe.kernel.event.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.util.JsonUtil;

/**
 * sealed Event 的 Jackson 多态 round-trip（ADR-0006 §3.6 / B3-T1 遗留覆盖）。
 *
 * <p>验证 {@link Event} 上的 {@code @JsonTypeInfo} + {@code @JsonSubTypes} 对 4 个子类型都能正确
 * 序列化/反序列化：序列化时写出 {@code "@type": "CdcEvent"|...}，反序列化时按标识还原具体子类型。
 * 重点：{@link DelayedEvent} 嵌套 {@code Event}（递归多态）必须 round-trip。
 *
 * <p>相等性采用"再序列化后 JSON 一致"判断（{@code serialize(round-trip) == serialize(original)}），
 * 而非 record equals：{@code Map<String,Object>} 里 Long/Integer 在 JSON 里有归一形态，
 * 但 Jackson 反序列化时按数值大小落回 Integer/Long，与原 Map 字面量类型未必一致（如 1L 反序列化为 Integer 1），
 * 这是 Jackson 通用行为而非本 ADR 的缺陷；用 canonical JSON 比对消除该噪音，聚焦多态 dispatch 正确性。
 */
class EventJacksonRoundTripTest {

    @Test
    void cdcEventRoundTrips() {
        CdcEvent original = new CdcEvent(
                new CdcMeta("ibm-mq://orders", "trade", "orders", Map.of("txn", "t-1")),
                Map.of("id", 1L),
                Map.of("id", 1L, "amount", 2000L),
                CdcOp.UPDATE,
                Instant.parse("2026-07-18T10:00:00Z"));

        String json = JsonUtil.toJson(original);
        // @type 标识必须写出，否则反序列化无类型线索
        assertThat(json).contains("\"@type\":\"CdcEvent\"");

        Event roundTripped = JsonUtil.fromJson(json, Event.class);
        assertThat(roundTripped).isInstanceOf(CdcEvent.class);
        assertThat(JsonUtil.toJson(roundTripped)).isEqualTo(json);
    }

    @Test
    void tickEventRoundTrips() {
        TickEvent original = new TickEvent(
                new TickMeta("cron:orders-tick", "orders-tick", "0 */5 * * * *", Map.of()), Instant.now());

        String json = JsonUtil.toJson(original);
        assertThat(json).contains("\"@type\":\"TickEvent\"");

        Event roundTripped = JsonUtil.fromJson(json, Event.class);
        assertThat(roundTripped).isInstanceOf(TickEvent.class).isEqualTo(original);
    }

    @Test
    void apiEventRoundTrips() {
        ApiEvent original = new ApiEvent(
                new ApiMeta("http://api/orders", "orders", Map.of("header", "X")),
                Map.of("orderId", 42L, "action", "submit"),
                Instant.parse("2026-07-18T11:00:00Z"));

        String json = JsonUtil.toJson(original);
        assertThat(json).contains("\"@type\":\"ApiEvent\"");

        Event roundTripped = JsonUtil.fromJson(json, Event.class);
        assertThat(roundTripped).isInstanceOf(ApiEvent.class);
        assertThat(JsonUtil.toJson(roundTripped)).isEqualTo(json);
    }

    @Test
    void delayedEventRoundTripsWithNestedCdcEvent() {
        // Delayed 嵌套 Cdc ——递归多态：外层 @type=DelayedEvent，内层 originalEvent 也带 @type=CdcEvent
        CdcEvent nested = new CdcEvent(
                new CdcMeta("ibm-mq://orders", "trade", "orders", Map.of()),
                null,
                Map.of("id", 9L, "amount", 50L),
                CdcOp.INSERT,
                Instant.parse("2026-07-18T09:00:00Z"));
        DelayedEvent original = new DelayedEvent(
                new DelayedMeta(
                        "delayed:42",
                        Map.of(
                                "schedule_id", 100L,
                                "subscription_id", 7L,
                                "scheduled_at", "2026-07-18T09:00:05Z",
                                "fired_at", "2026-07-18T09:00:10Z",
                                "correlation_key", "order-9")),
                nested,
                Instant.parse("2026-07-18T09:00:10Z"));

        String json = JsonUtil.toJson(original);
        assertThat(json).contains("\"@type\":\"DelayedEvent\"");
        // 内层 originalEvent 也必须带 @type=CdcEvent，否则反序列化得到的是抽象 Event（失败）
        assertThat(json).contains("\"@type\":\"CdcEvent\"");

        Event roundTripped = JsonUtil.fromJson(json, Event.class);
        assertThat(roundTripped).isInstanceOf(DelayedEvent.class);
        // 显式验证嵌套事件被还原成 CdcEvent 子类型（而非 null/LinkedHashMap）
        DelayedEvent delayed = (DelayedEvent) roundTripped;
        assertThat(delayed.originalEvent()).isInstanceOf(CdcEvent.class);
        assertThat(JsonUtil.toJson(roundTripped)).isEqualTo(json);
    }

    @Test
    void delayedEventRoundTripsWithNestedApiEvent() {
        // 嵌套非 CDC 子类型同样要 round-trip（覆盖 @JsonSubTypes 全部条目）
        ApiEvent nested = new ApiEvent(
                new ApiMeta("http://api/orders", "orders", Map.of()),
                Map.of("orderId", 11L),
                Instant.parse("2026-07-18T12:00:00Z"));
        DelayedEvent original =
                new DelayedEvent(new DelayedMeta("delayed:11", Map.of("subscription_id", 3L)), nested, Instant.now());

        String json = JsonUtil.toJson(original);
        assertThat(json).contains("\"@type\":\"DelayedEvent\"").contains("\"@type\":\"ApiEvent\"");

        DelayedEvent roundTripped = (DelayedEvent) JsonUtil.fromJson(json, Event.class);
        assertThat(roundTripped.originalEvent()).isInstanceOf(ApiEvent.class);
        assertThat(JsonUtil.toJson(roundTripped)).isEqualTo(json);
    }
}
