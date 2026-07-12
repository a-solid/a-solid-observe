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
        Event event = JsonUtil.fromJson(req.eventJson(), Event.class);
        return DryRunResultDto.from(dryRunService.run(pipeline, event));
    }

    public record ValidatePipelineRequest(String pipelineJson) {}

    public record DryRunRequest(String pipelineJson, String eventJson) {}
}
