package com.imsw.observe.pipeline.application;

import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.pipeline.domain.Pipeline;

public interface PipelineExecutor {

    PipelineOutcome execute(Pipeline pipeline, ExecutionContext ctx);
}
