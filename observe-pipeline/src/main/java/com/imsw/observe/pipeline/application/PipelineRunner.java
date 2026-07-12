package com.imsw.observe.pipeline.application;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.domain.Pipeline;

public interface PipelineRunner {

    void run(Pipeline pipeline, Event triggerEvent, String subscriptionId);
}
