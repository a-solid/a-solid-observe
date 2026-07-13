package com.imsw.observe.controlplane.interfaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.infrastructure.source.ApiSource;

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
        Map<String, Object> after = Map.of("id", 123, "status", "PAID");
        // record 字段顺序: (source, table, op, before, after, attributes)
        EventController.SubmitEventRequest req =
                new EventController.SubmitEventRequest("order-service", "orders", "UPDATE", null, after, Map.of());

        EventController.SubmitEventResponse resp = controller.submit(req);

        // submit 被调用一次
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(apiSource, times(1)).submit(captor.capture());
        Event submitted = captor.getValue();

        // 字段映射
        assertEquals(SourceType.API, submitted.meta().sourceType());
        assertEquals("order-service", submitted.meta().source());
        assertEquals("orders", submitted.meta().table());
        assertEquals(Op.UPDATE, submitted.op());
        assertEquals(after, submitted.after());
        assertNotNull(submitted.sourceTs());
        // eventId 放进 attributes
        String eventId = (String) submitted.meta().attributes().get("eventId");
        assertNotNull(eventId);
        assertTrue(!eventId.isEmpty());
        // 响应里的 eventId 与提交进 Event 的一致
        assertEquals(eventId, resp.eventId());
    }

    @Test
    void submitDefaultsOpToInsertWhenNull() {
        EventController.SubmitEventRequest req =
                new EventController.SubmitEventRequest("svc", null, null, null, null, null);

        controller.submit(req);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(apiSource).submit(captor.capture());
        assertEquals(Op.INSERT, captor.getValue().op());
    }

    @Test
    void submitRejectsMissingSource() {
        EventController.SubmitEventRequest req =
                new EventController.SubmitEventRequest(null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> controller.submit(req));
        verify(apiSource, times(0)).submit(any(Event.class));
    }
}
