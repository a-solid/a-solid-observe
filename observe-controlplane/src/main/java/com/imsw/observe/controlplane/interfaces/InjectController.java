package com.imsw.observe.controlplane.interfaces;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.controlplane.interfaces.dto.InjectResultDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.ErrorResponseException;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.util.JsonUtil;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.domain.Pipeline;

/**
 * 手动注入事件触发 pipeline 执行（真落库）。
 *
 * <p>与 {@code /validate/dry-run}（试跑、事务回滚、告警截获不落库）的区别：inject 走<b>生产 runner</b>
 * （真实 AlertSink + ExecutionRecorder + 真事务），pipeline 执行产出告警<b>真实落库</b>、execution 记录真实写入。
 * 用于"用自定义数据手动触发一次告警"——例如排错时灌一条 CDC 事件验证脚本 + 看告警是否生成。
 *
 * <p>按业务键 {@code (namespace, name)} 从运行态 registry 取已加载的（已发布 + 已热加载）Pipeline——与其它
 * controller 对齐（CONTEXT.md "对外 API 用业务键寻址" 铁律）；不在 registry 则 404（提示未发布或未热加载）。
 * {@code eventJson} 须带 {@code @type} discriminator（同 dry-run）。
 */
@RestController
@RequestMapping("/api/v1")
public class InjectController {

    private final PipelineRegistry registry;

    private final PipelineRunner runner;

    public InjectController(final PipelineRegistry registry, final PipelineRunner runner) {
        this.registry = registry;
        this.runner = runner;
    }

    @PostMapping("/namespaces/{namespace}/pipelines/{name}/inject")
    public ApiResponse<InjectResultDto> inject(
            @PathVariable final String namespace,
            @PathVariable final String name,
            @Valid @RequestBody final InjectRequest req) {
        Pipeline pipeline = registry.snapshot().pipelineByNamespaceAndName(namespace, name);
        if (pipeline == null) {
            throw new ResourceNotFoundException("pipeline " + namespace + "/" + name
                    + " not loaded in registry (not published or not hot-reloaded)");
        }
        Event event = deserializeEvent(req.eventJson());
        String outcome;
        try {
            runner.run(pipeline, event, null);
            outcome = "SUCCESS";
        } catch (RuntimeException e) {
            outcome = "FAILED";
        }
        return ApiResponse.ok(new InjectResultDto(outcome));
    }

    /** 反序列化 Event，缺/错 @type 给明确错误（同 dry-run）。 */
    private static Event deserializeEvent(final String eventJson) {
        if (eventJson == null || eventJson.isBlank()) {
            throw badRequest();
        }
        try {
            Event event = JsonUtil.fromJson(eventJson, Event.class);
            if (event == null) {
                throw badRequest();
            }
            return event;
        } catch (RuntimeException e) {
            throw new ErrorResponseException(
                    com.imsw.observe.controlplane.interfaces.web.ErrorCode.BAD_REQUEST.httpStatus(),
                    com.imsw.observe.controlplane.interfaces.web.ErrorCode.BAD_REQUEST,
                    "eventJson must include \"@type\":\"CdcEvent\"|\"TickEvent\"|\"ApiEvent\"|\"DelayedEvent\""
                            + " (failed to deserialize: " + e.getMessage() + ")",
                    e);
        }
    }

    private static ErrorResponseException badRequest() {
        return new ErrorResponseException(
                com.imsw.observe.controlplane.interfaces.web.ErrorCode.BAD_REQUEST.httpStatus(),
                com.imsw.observe.controlplane.interfaces.web.ErrorCode.BAD_REQUEST,
                "eventJson must include \"@type\":\"CdcEvent\"|\"TickEvent\"|\"ApiEvent\"|\"DelayedEvent\"");
    }

    public record InjectRequest(@NotBlank String eventJson) {}
}
