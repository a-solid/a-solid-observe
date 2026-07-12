package com.imsw.observe.pipeline.application;

import com.imsw.observe.kernel.error.NodeExecutionException;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.pipeline.domain.NodeOutcome;
import com.imsw.observe.pipeline.domain.NodeSpec;

public interface Node {

    NodeOutcome execute(NodeSpec spec, ExecutionContext ctx) throws NodeExecutionException;
}
