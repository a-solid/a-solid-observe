package com.imsw.observe.controlplane.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.imsw.observe.config.application.PipelineValidator;
import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.application.DryRunService;
import com.imsw.observe.pipeline.domain.Pipeline;

/**
 * ValidateController /dry-run 的契约测试（B3 final review Finding #1）。
 *
 * <p>{@code Event} 是 {@code @JsonTypeInfo(property="@type")} 多态 sealed interface：
 * 客户端 {@code eventJson} 必须带 {@code "@type":"CdcEvent"|...} discriminator，否则 Jackson 无法
 * dispatch 子类型。本测试明确记录该契约——合法 {@code @type} 可成功 deserialization + 触发 dry-run；
 * 缺/空 {@code eventJson} 与缺/错 {@code @type} 必须以明确的 {@link IllegalArgumentException}
 * 失败（消息点明合法取值），而非落到不透明的 500。
 */
class ValidateControllerTest {

    private static final String CDC_EVENT_JSON =
            """
            {"@type":"CdcEvent","meta":{"source":"ibm-mq://orders","db":"trade","table":"orders","attributes":{"txn":"t-1"}},\
"before":{"id":1},"after":{"id":1,"amount":2000},"op":"UPDATE","sourceTs":"2026-07-18T10:00:00Z"}""";

    private static final String MISSING_TYPE_EVENT_JSON =
            """
            {"meta":{"source":"ibm-mq://orders","db":"trade","table":"orders"},\
"after":{"id":1,"amount":2000},"op":"UPDATE","sourceTs":"2026-07-18T10:00:00Z"}""";

    private static final String BAD_TYPE_EVENT_JSON = """
            {"@type":"NoSuchSubtype","after":{"id":1}}""";

    private static final String MINIMAL_PIPELINE_JSON =
            """
            {"id":1,"namespace":"smoke","version":1,"team":"smoke-team","application":"smoke-app",\
"labels":{},"name":"smoke","status":"PUBLISHED","nodes":[],"createdAt":"2026-07-18T10:00:00Z",\
"publishedAt":"2026-07-18T10:00:00Z","executionLogSampleRatio":1.0}""";

    private PipelineValidator validator;

    private DryRunService dryRunService;

    private ValidateController controller;

    @BeforeEach
    void setUp() {
        validator = mock(PipelineValidator.class);
        dryRunService = mock(DryRunService.class);
        controller = new ValidateController(validator, dryRunService);
    }

    @Test
    void dryRunWithAtTypeCdcEventDeserializesAndRuns() {
        DryRunService.DryRunResult stub = new DryRunService.DryRunResult("SUCCESS", List.of());
        when(dryRunService.run(any(Pipeline.class), any(Event.class))).thenReturn(stub);

        var resp = controller.dryRun(new ValidateController.DryRunRequest(MINIMAL_PIPELINE_JSON, CDC_EVENT_JSON));

        // dryRunService.run 被调用，且 event 参数被还原为 CdcEvent 子类型（证明 @type dispatch 成功）
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(dryRunService, times(1)).run(any(Pipeline.class), captor.capture());
        Event passed = captor.getValue();
        assertThat(passed).isInstanceOf(CdcEvent.class);
        assertThat(((CdcEvent) passed).op()).isEqualTo(CdcOp.UPDATE);
        assertThat(((CdcEvent) passed).meta().db()).isEqualTo("trade");
        assertThat(((CdcEvent) passed).after()).containsEntry("amount", 2000);
        assertThat(passed.sourceTs()).isEqualTo(Instant.parse("2026-07-18T10:00:00Z"));
        // 响应透传 outcome（B5：解包 ApiResponse.data）
        assertThat(resp.data().outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void dryRunWithoutAtTypeThrowsBadRequestWithHint() {
        // 缺 @type：Jackson 无法 dispatch 子类型，必须以明确 ErrorResponseException(BAD_REQUEST) 失败（而非 opaque 500）
        assertThatThrownBy(() -> controller.dryRun(
                        new ValidateController.DryRunRequest(MINIMAL_PIPELINE_JSON, MISSING_TYPE_EVENT_JSON)))
                .isInstanceOf(com.imsw.observe.controlplane.interfaces.web.ErrorResponseException.class)
                .hasMessageContaining("\"@type\"")
                .hasMessageContaining("CdcEvent")
                .hasMessageContaining("TickEvent")
                .hasMessageContaining("ApiEvent")
                .hasMessageContaining("DelayedEvent");
        // dry-run 不可执行：deserialization 失败前不应触发 run
        verify(dryRunService, times(0)).run(any(Pipeline.class), any(Event.class));
    }

    @Test
    void dryRunWithUnknownAtTypeThrowsBadRequestWithHint() {
        // @type 指向非注册子类型：Jackson 同样无法 dispatch，应给同样清晰的错误
        assertThatThrownBy(() -> controller.dryRun(
                        new ValidateController.DryRunRequest(MINIMAL_PIPELINE_JSON, BAD_TYPE_EVENT_JSON)))
                .isInstanceOf(com.imsw.observe.controlplane.interfaces.web.ErrorResponseException.class)
                .hasMessageContaining("\"@type\"")
                .hasMessageContaining("CdcEvent");
        verify(dryRunService, times(0)).run(any(Pipeline.class), any(Event.class));
    }

    @Test
    void dryRunWithBlankEventJsonThrowsBadRequestWithHint() {
        // 空/blank eventJson：同样给明确的 discriminator 提示，而非走 JsonUtil.fromJson 的 null 静默路径
        assertThatThrownBy(() -> controller.dryRun(new ValidateController.DryRunRequest(MINIMAL_PIPELINE_JSON, "  ")))
                .isInstanceOf(com.imsw.observe.controlplane.interfaces.web.ErrorResponseException.class)
                .hasMessageContaining("\"@type\"")
                .hasMessageContaining("CdcEvent");
        verify(dryRunService, times(0)).run(any(Pipeline.class), eq((Event) null));
        verify(dryRunService, times(0)).run(any(Pipeline.class), any(Event.class));
    }
}
