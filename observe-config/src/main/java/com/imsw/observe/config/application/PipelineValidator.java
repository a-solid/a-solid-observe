package com.imsw.observe.config.application;

import java.util.ArrayList;
import java.util.List;

import com.imsw.observe.kernel.script.spi.GroovyScriptEngine;
import com.imsw.observe.pipeline.domain.NodeSpec;
import com.imsw.observe.pipeline.domain.Pipeline;

public final class PipelineValidator {

    private final GroovyScriptEngine engine;

    public PipelineValidator(final GroovyScriptEngine engine) {
        this.engine = engine;
    }

    public ValidationResult validate(final Pipeline pipeline) {
        List<String> errors = new ArrayList<>();
        if (pipeline == null) {
            errors.add("pipeline is null");
            return new ValidationResult(false, errors);
        }
        if (pipeline.nodes() == null || pipeline.nodes().isEmpty()) {
            errors.add("pipeline has no nodes");
        }
        if (pipeline.nodes() != null) {
            for (NodeSpec node : pipeline.nodes()) {
                if (node.scriptSource() == null || node.scriptSource().isBlank()) {
                    errors.add("node " + node.name() + " has empty script");
                    continue;
                }
                try {
                    engine.compile(node.scriptSource());
                } catch (RuntimeException e) {
                    errors.add("node " + node.name() + " script invalid: " + e.getMessage());
                }
            }
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    public record ValidationResult(boolean ok, List<String> errors) {}
}
