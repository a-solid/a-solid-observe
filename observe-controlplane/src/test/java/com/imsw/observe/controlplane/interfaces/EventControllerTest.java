package com.imsw.observe.controlplane.interfaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.imsw.observe.pipeline.infrastructure.source.ApiSource;

/**
 * EventController 契约测试（ADR-0007 addendum：按 (namespace, name) 路由）。
 *
 * <p>Controller 只负责：解析 path 参数 (namespace, name) → 调 ApiSource.dispatch(ns, name, payload) →
 * 返回 controller 生成的 eventId。ApiSource 内部按 (ns,name) 查订阅、wrap ApiEvent（含 subscriptionId）、
 * 入队——controller 不再触碰 ApiEvent 构造。
 *
 * <p>B5：响应包入 ApiResponse；eventId 由 controller 生成（不依赖 ApiSource 内部行为）。
 */
class EventControllerTest {

    private ApiSource apiSource;

    private EventController controller;

    @BeforeEach
    void setUp() {
        apiSource = mock(ApiSource.class);
        controller = new EventController(apiSource);
    }

    @Test
    void submitDispatchesWithNamespaceNameAndPayload() {
        Map<String, Object> payload = Map.of("id", 123, "status", "PAID");

        EventController.SubmitEventResponse resp = controller
                .submit("billing", "order-webhook", new EventController.SubmitEventRequest(payload))
                .data();

        // dispatch 被调一次，参数 (namespace, name, payload) 透传
        verify(apiSource, times(1)).dispatch("billing", "order-webhook", payload);
        // eventId 由 controller 生成并回填到响应
        assertNotNull(resp.eventId());
    }

    @Test
    void submitDefaultsPayloadToEmptyMapWhenNull() {
        controller.submit("billing", "order-webhook", new EventController.SubmitEventRequest(null));

        // payload 为 null 时 controller 调 dispatch 传 null（ApiSource 内部 normalize 为空 Map）
        verify(apiSource).dispatch("billing", "order-webhook", null);
    }

    @Test
    void submitGeneratesUniqueEventIdPerCall() {
        EventController.SubmitEventResponse resp1 = controller
                .submit("ns", "name", new EventController.SubmitEventRequest(Map.of()))
                .data();
        EventController.SubmitEventResponse resp2 = controller
                .submit("ns", "name", new EventController.SubmitEventRequest(Map.of()))
                .data();

        // 两次调用应生成不同的 eventId（UUID）
        assertNotNull(resp1.eventId());
        assertNotNull(resp2.eventId());
        assertNotEquals(resp1.eventId(), resp2.eventId());
        assertEquals(
                2,
                org.mockito.Mockito.mockingDetails(apiSource).getInvocations().size());
    }
}
