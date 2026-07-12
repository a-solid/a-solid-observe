package com.imsw.observe.pipeline.infrastructure.engine;

import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.alert.spi.AlertsApi;
import com.imsw.observe.kernel.error.NodeExecutionException;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.script.spi.DbApi;
import com.imsw.observe.kernel.script.spi.GroovyScriptEngine;
import com.imsw.observe.pipeline.application.Node;
import com.imsw.observe.pipeline.domain.NodeOutcome;
import com.imsw.observe.pipeline.domain.NodeSpec;
import com.imsw.observe.pipeline.infrastructure.script.DefaultExecutionContext;
import com.imsw.observe.pipeline.infrastructure.script.DefaultScriptContext;

public final class ScriptNode implements Node {

    private static final Logger LOG = LoggerFactory.getLogger(ScriptNode.class);

    private final GroovyScriptEngine engine;
    private final Function<ExecutionContext, AlertsApi> alertsApiFactory;
    private final Supplier<DbApi> dbApiSupplier;

    public ScriptNode(
            final GroovyScriptEngine engine,
            final Function<ExecutionContext, AlertsApi> alertsApiFactory,
            final Supplier<DbApi> dbApiSupplier) {
        this.engine = engine;
        this.alertsApiFactory = alertsApiFactory;
        this.dbApiSupplier = dbApiSupplier;
    }

    @Override
    public NodeOutcome execute(final NodeSpec spec, final ExecutionContext ctx) throws NodeExecutionException {
        DefaultExecutionContext defaultCtx = (DefaultExecutionContext) ctx;
        defaultCtx.enterNode(spec.name());
        DefaultScriptContext scriptCtx = defaultCtx.scriptContext();
        if (scriptCtx.get("event") == null) {
            Event trigger = ctx.data().event;
            scriptCtx.putGlobal("event", trigger);
            scriptCtx.putGlobal("ctx", scriptCtx);
            scriptCtx.putGlobal("alerts", alertsApiFactory.apply(ctx));
            scriptCtx.putGlobal("db", dbApiSupplier.get());
            scriptCtx.putGlobal("now", (Supplier<Instant>) Instant::now);
        }
        try {
            Object result = engine.execute(spec.scriptSource(), scriptCtx);
            if (Boolean.TRUE.equals(result)) {
                LOG.debug("node {} short-circuited", spec.name());
                return NodeOutcome.SHORT_CIRCUIT;
            }
            return NodeOutcome.CONTINUE;
        } catch (RuntimeException e) {
            throw new NodeExecutionException(spec.name(), "Node " + spec.name() + " failed: " + e.getMessage(), e);
        }
    }
}
