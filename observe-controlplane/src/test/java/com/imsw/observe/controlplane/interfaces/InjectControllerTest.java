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
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.domain.Pipeline;

/**
 * InjectController 契约测试：按业务键 {@code (namespace, name)} 寻址 pipeline（CONTEXT.md "对外 API
 * 用业务键寻址" 铁律）。覆盖 200（成功 / 失败 outcome）+ 404（pipeline 未加载）+ 反序列化失败路径。
 */
class InjectControllerTest {

    private static final String CDC_EVENT_JSON =
            """
            {"@type":"CdcEvent","meta":{"source":"ibm-mq://orders","db":"trade","table":"orders",\
"attributes":{"txn":"t-1"}},"before":{"id":1},"after":{"id":1,"amount":2000},"op":"UPDATE",\
"sourceTs":"2026-07-18T10:00:00Z"}""";

    private PipelineRegistry registry;

    private PipelineRunner runner;

    private InjectController controller;

    @BeforeEach
    void setUp() {
        registry = mock(PipelineRegistry.class);
        runner = mock(PipelineRunner.class);
        controller = new InjectController(registry, runner);
    }

    @Test
    void injectRunsPipelineAddressedByNamespaceAndName() {
        Pipeline pipeline = stubPipeline("billing", "order-check");
        when(registry.snapshot()).thenReturn(snapshotWith(pipeline));

        var resp = controller.inject("billing", "order-check", new InjectController.InjectRequest(CDC_EVENT_JSON));

        // runner.run 被调一次，pipeline 是按 (ns,name) 查到的，subscriptionId 为 null（手动注入无订阅）
        verify(runner, times(1)).run(eq(pipeline), any(Event.class), eq(null));
        assertThat(resp.data().outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void injectReturnsFailedOutcomeWhenRunnerThrows() {
        Pipeline pipeline = stubPipeline("billing", "order-check");
        when(registry.snapshot()).thenReturn(snapshotWith(pipeline));
        // runner 抛 RuntimeException——controller 不向外冒泡，转 outcome=FAILED
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(runner)
                .run(eq(pipeline), any(Event.class), eq(null));

        var resp = controller.inject("billing", "order-check", new InjectController.InjectRequest(CDC_EVENT_JSON));

        assertThat(resp.data().outcome()).isEqualTo("FAILED");
    }

    @Test
    void injectThrowsNotFoundWhenPipelineNotLoaded() {
        // snapshot 不含 (billing, missing) —— 404，且不调 runner
        when(registry.snapshot()).thenReturn(snapshotWith(stubPipeline("other", "pipeline")));

        assertThatThrownBy(() ->
                        controller.inject("billing", "missing", new InjectController.InjectRequest(CDC_EVENT_JSON)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("billing/missing");
        verify(runner, times(0)).run(any(Pipeline.class), any(Event.class), any());
    }

    @Test
    void injectRejectsBlankEventJsonWithBadRequest() {
        Pipeline pipeline = stubPipeline("billing", "order-check");
        when(registry.snapshot()).thenReturn(snapshotWith(pipeline));

        // 空 eventJson —— ErrorResponseException(BAD_REQUEST)，不调 runner
        assertThatThrownBy(() -> controller.inject("billing", "order-check", new InjectController.InjectRequest("  ")))
                .isInstanceOf(com.imsw.observe.controlplane.interfaces.web.ErrorResponseException.class)
                .hasMessageContaining("\"@type\"");
        verify(runner, times(0)).run(any(Pipeline.class), any(Event.class), any());
    }

    private static Pipeline stubPipeline(final String namespace, final String name) {
        return new Pipeline(
                1L,
                namespace,
                1,
                Map.of(),
                name,
                Pipeline.Status.PUBLISHED,
                List.of(),
                Instant.now(),
                Instant.now(),
                0.0);
    }

    private static PipelineRegistry.Snapshot snapshotWith(final Pipeline pipeline) {
        return PipelineRegistry.Snapshot.loaded(Map.of(pipeline.id(), pipeline), List.of());
    }
}
