package com.imsw.observe.pipeline.infrastructure.engine;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.error.NodeExecutionException;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.pipeline.application.Node;
import com.imsw.observe.pipeline.application.PipelineExecutor;
import com.imsw.observe.pipeline.application.PipelineOutcome;
import com.imsw.observe.pipeline.domain.ErrorPolicy;
import com.imsw.observe.pipeline.domain.NodeOutcome;
import com.imsw.observe.pipeline.domain.NodeSpec;
import com.imsw.observe.pipeline.domain.Pipeline;

public final class LinearPipelineExecutor implements PipelineExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(LinearPipelineExecutor.class);

    private final Function<NodeSpec, Node> nodeFactory;

    public LinearPipelineExecutor(final Function<NodeSpec, Node> nodeFactory) {
        this.nodeFactory = nodeFactory;
    }

    @Override
    public PipelineOutcome execute(final Pipeline pipeline, final ExecutionContext ctx) {
        for (NodeSpec spec : pipeline.nodes()) {
            NodeOutcome outcome = runNode(pipeline.id(), spec, ctx);
            if (outcome == NodeOutcome.SHORT_CIRCUIT) {
                LOG.info("pipeline {} short-circuited at node {}", pipeline.id(), spec.name());
                return PipelineOutcome.SHORT_CIRCUITED;
            }
        }
        return PipelineOutcome.SUCCESS;
    }

    private NodeOutcome runNode(final Long pipelineId, final NodeSpec spec, final ExecutionContext ctx) {
        Node node = nodeFactory.apply(spec);
        try {
            return node.execute(spec, ctx);
        } catch (NodeExecutionException e) {
            if (spec.errorPolicy() == ErrorPolicy.SKIP_NODE) {
                LOG.warn(
                        "pipeline {} node {} failed, skipping per ErrorPolicy: {}",
                        pipelineId,
                        spec.name(),
                        e.getMessage());
                return NodeOutcome.CONTINUE;
            }
            LOG.error("pipeline {} node {} failed (FAIL policy)", pipelineId, spec.name(), e);
            throw e;
        }
    }
}
