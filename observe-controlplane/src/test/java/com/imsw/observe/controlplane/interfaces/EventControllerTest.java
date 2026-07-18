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
import org.mockito.ArgumentCaptor;

import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.kernel.event.model.ApiEvent;
import com.imsw.observe.pipeline.infrastructure.source.ApiSource;

/**
 * EventController 适配 sealed ApiEvent（ADR-0006 §3.5）后的契约测试。
 *
 * <p>Controller 把 {@code SubmitEventRequest(source, payload, attributes)} 映射成 {@link ApiEvent}：
 * apiName=source、payload 透传、attributes 带 controller 生成的 eventId。不再有 table/op/before/after 概念
 * （那些是 CDC 事件语义，ApiEvent 只有 payload + apiName）。
 *
 * <p>B5：响应包入 {@link ApiResponse}；source 的必填校验改为 {@code @NotBlank}，由 Spring MVC 层触发，
 * 相关「拒绝缺/空 source」断言移至 {@code GlobalExceptionHandlerWebTest}（MockMvc）。
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
    void submitMapsFieldsAndReturnsAccepted() {
        Map<String, Object> payload = Map.of("id", 123, "status", "PAID");
        // record 字段顺序: (source, payload, attributes)
        EventController.SubmitEventRequest req =
                new EventController.SubmitEventRequest("order-service", payload, Map.of());

        EventController.SubmitEventResponse resp = controller.submit(req).data();

        // submit 被调用一次，参数是 ApiEvent
        ArgumentCaptor<ApiEvent> captor = ArgumentCaptor.forClass(ApiEvent.class);
        verify(apiSource, times(1)).submit(captor.capture());
        ApiEvent apiEvent = captor.getValue();
        // apiName == 提交的 source（显式字段便于脚本/索引）
        assertEquals("order-service", apiEvent.meta().apiName());
        // source 标识保留 "api:" + source 前缀（来自 EventController）
        assertEquals("api:order-service", apiEvent.meta().source());
        // payload 透传
        assertEquals(payload, apiEvent.payload());
        // sourceTs 由 controller 填充
        assertNotNull(apiEvent.sourceTs());
        // eventId 放进 attributes
        String eventId = (String) apiEvent.meta().attributes().get("eventId");
        assertNotNull(eventId);
        // 响应里的 eventId 与提交进 Event 的一致
        assertEquals(eventId, resp.eventId());
    }

    @Test
    void submitDefaultsPayloadToEmptyMapWhenNull() {
        EventController.SubmitEventRequest req = new EventController.SubmitEventRequest("svc", null, null);

        controller.submit(req);

        ArgumentCaptor<ApiEvent> captor = ArgumentCaptor.forClass(ApiEvent.class);
        verify(apiSource).submit(captor.capture());
        ApiEvent apiEvent = captor.getValue();
        // payload 为 null 时 controller 给空 Map，不抛 NPE
        assertEquals(Map.of(), apiEvent.payload());
        // 即使 attributes 为 null，eventId 也必须存在
        assertNotNull(apiEvent.meta().attributes().get("eventId"));
    }

    @Test
    void submitOverwritesClientSuppliedEventId() {
        EventController.SubmitEventRequest req =
                new EventController.SubmitEventRequest("svc", null, Map.of("eventId", "client-supplied"));

        EventController.SubmitEventResponse resp = controller.submit(req).data();

        ArgumentCaptor<ApiEvent> captor = ArgumentCaptor.forClass(ApiEvent.class);
        verify(apiSource).submit(captor.capture());
        ApiEvent apiEvent = captor.getValue();
        String submittedEventId = (String) apiEvent.meta().attributes().get("eventId");
        assertNotNull(submittedEventId);
        assertNotEquals("client-supplied", submittedEventId);
        assertEquals(submittedEventId, resp.eventId());
    }
}
