package com.imsw.observe.controlplane.interfaces;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.config.application.PipelineValidator;
import com.imsw.observe.controlplane.interfaces.dto.DryRunResultDto;
import com.imsw.observe.controlplane.interfaces.dto.ValidationResultDto;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.util.JsonUtil;
import com.imsw.observe.pipeline.application.DryRunService;
import com.imsw.observe.pipeline.domain.Pipeline;

@RestController
@RequestMapping("/api/v1/validate")
public class ValidateController {

    private final PipelineValidator validator;

    private final DryRunService dryRunService;

    public ValidateController(final PipelineValidator validator, final DryRunService dryRunService) {
        this.validator = validator;
        this.dryRunService = dryRunService;
    }

    @PostMapping("/pipeline")
    public ValidationResultDto validatePipeline(@RequestBody final ValidatePipelineRequest req) {
        Pipeline pipeline = JsonUtil.fromJson(req.pipelineJson(), Pipeline.class);
        return ValidationResultDto.from(validator.validate(pipeline));
    }

    @PostMapping("/dry-run")
    public DryRunResultDto dryRun(@RequestBody final DryRunRequest req) {
        Pipeline pipeline = JsonUtil.fromJson(req.pipelineJson(), Pipeline.class);
        Event event = deserializeEvent(req.eventJson());
        return DryRunResultDto.from(dryRunService.run(pipeline, event));
    }

    /**
     * 反序列化 {@link Event}，对缺/错 {@code @type} discriminator 给出明确错误（而非 opaque 500）。
     *
     * <p>{@link Event} 是 {@code @JsonTypeInfo(property="@type")} 多态 sealed interface：客户端的
     * {@code eventJson} 必须带 {@code "@type": "CdcEvent"|"TickEvent"|"ApiEvent"|"DelayedEvent"}，否则
     * Jackson 无法 dispatch 子类型 → {@link JsonUtil#fromJson} 抛 {@link IllegalStateException} → 模块
     * 无 {@code @RestControllerAdvice}（约定为 IllegalArgumentException→500），最终落到不透明的 500。
     * 这里把 deserialization 失败转成 {@link IllegalArgumentException}（沿用模块约定），消息里明确告诉
     * 调用方缺哪个字段、合法取值是哪些。
     */
    private static Event deserializeEvent(final String eventJson) {
        if (eventJson == null || eventJson.isBlank()) {
            throw new IllegalArgumentException(
                    "eventJson must include \"@type\":\"CdcEvent\"|\"TickEvent\"|\"ApiEvent\"|\"DelayedEvent\"");
        }
        try {
            Event event = JsonUtil.fromJson(eventJson, Event.class);
            if (event == null) {
                throw new IllegalArgumentException(
                        "eventJson must include \"@type\":\"CdcEvent\"|\"TickEvent\"|\"ApiEvent\"|\"DelayedEvent\"");
            }
            return event;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "eventJson must include \"@type\":\"CdcEvent\"|\"TickEvent\"|\"ApiEvent\"|\"DelayedEvent\""
                            + " (failed to deserialize: " + e.getMessage() + ")",
                    e);
        }
    }

    public record ValidatePipelineRequest(String pipelineJson) {}

    public record DryRunRequest(String pipelineJson, String eventJson) {}
}
