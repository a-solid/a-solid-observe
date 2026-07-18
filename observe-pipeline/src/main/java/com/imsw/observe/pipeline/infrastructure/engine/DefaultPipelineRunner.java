package com.imsw.observe.pipeline.infrastructure.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.alert.spi.AlertSink;
import com.imsw.observe.kernel.error.NodeExecutionException;
import com.imsw.observe.kernel.error.ScriptCompilationException;
import com.imsw.observe.kernel.error.ScriptExecutionException;
import com.imsw.observe.kernel.error.ScriptSandboxException;
import com.imsw.observe.kernel.error.ScriptTimeoutException;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionData;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.execution.model.ErrorType;
import com.imsw.observe.kernel.execution.spi.ExecutionRecorder;
import com.imsw.observe.kernel.transaction.spi.TransactionOperator;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;
import com.imsw.observe.pipeline.application.PipelineExecutor;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.infrastructure.script.DefaultExecutionContext;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

public final class DefaultPipelineRunner implements PipelineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultPipelineRunner.class);

    private static final Tracer TRACER = GlobalOpenTelemetry.get().getTracer("observe.pipeline");

    private static final LongCounter EXECUTIONS = GlobalOpenTelemetry.get()
            .getMeter("observe.pipeline")
            .counterBuilder("pipeline.executions")
            .setDescription("Pipeline execution count by outcome")
            .build();

    private static final LongCounter FAILURES = GlobalOpenTelemetry.get()
            .getMeter("observe.pipeline")
            .counterBuilder("pipeline.failures")
            .setDescription("Pipeline execution failures by error type")
            .build();

    private final PipelineExecutor executor;
    private final AlertSink alertSink;
    private final TransactionOperator transactionOperator;
    private final ExecutionRecorder executionRecorder;

    private final SnowflakeIdGenerator snowflake;

    public DefaultPipelineRunner(
            final PipelineExecutor executor,
            final AlertSink alertSink,
            final TransactionOperator transactionOperator,
            final ExecutionRecorder executionRecorder,
            final SnowflakeIdGenerator snowflake) {
        this.executor = executor;
        this.alertSink = alertSink;
        this.transactionOperator = transactionOperator;
        this.executionRecorder = executionRecorder;
        this.snowflake = snowflake;
    }

    @Override
    public void run(final Pipeline pipeline, final Event triggerEvent, final Long subscriptionId) {
        ExecutionMeta meta = buildMeta(pipeline, triggerEvent, subscriptionId);
        ExecutionData data = new ExecutionData(triggerEvent);
        ExecutionContext ctx = new DefaultExecutionContext(meta, data);

        Instant start = Instant.now();
        String[] outcomeHolder = new String[1];
        Span span = TRACER.spanBuilder("pipeline " + pipeline.id()).startSpan();
        try (var scope = span.makeCurrent()) {
            transactionOperator.execute(() -> {
                String outcome = executor.execute(pipeline, ctx).name();
                alertSink.drainAndPersist(ctx);
                outcomeHolder[0] = outcome;
                span.setAttribute("outcome", outcome);
            });
            Duration duration = Duration.between(start, Instant.now());
            executionRecorder.recordSuccess(
                    ctx, outcomeHolder[0], duration, data.emittedAlert, pipeline.executionLogSampleRatio());
            count(EXECUTIONS, pipeline.id(), "status", outcomeHolder[0]);
            LOG.info("pipeline {} outcome={} duration_ms={}", pipeline.id(), outcomeHolder[0], duration.toMillis());
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            Duration duration = Duration.between(start, Instant.now());
            ErrorType errorType = classify(e);
            String nodeName = e instanceof NodeExecutionException nee ? nee.getNodeName() : null;
            executionRecorder.recordFailure(ctx, e, duration, nodeName, errorType);
            count(FAILURES, pipeline.id(), "error_type", errorType.name());
            LOG.error(
                    "pipeline {} failed duration_ms={} error_type={}",
                    pipeline.id(),
                    duration.toMillis(),
                    errorType,
                    e);
            throw e;
        } finally {
            span.end();
        }
    }

    private ExecutionMeta buildMeta(final Pipeline pipeline, final Event triggerEvent, final Long subscriptionId) {
        // 全链路 snowflake BIGINT id（ADR-0003）：execution/pipeline/subscription id 均为 Long，直接透传 kernel。
        Long executionId = snowflake.next();
        return new ExecutionMeta(
                executionId,
                pipeline.id(),
                pipeline.version(),
                pipeline.team(),
                pipeline.application(),
                pipeline.labels() == null ? Map.of() : pipeline.labels(),
                null,
                null,
                triggerEvent == null ? null : triggerEvent.meta().sourceType(),
                triggerEvent,
                Instant.now(),
                subscriptionId);
    }

    private static void count(final LongCounter counter, final Long pipelineId, final String tagKey, final String tag) {
        try {
            Attributes attrs = Attributes.of(
                    AttributeKey.stringKey("pipeline_id"),
                    String.valueOf(pipelineId),
                    AttributeKey.stringKey(tagKey),
                    tag);
            counter.add(1, attrs);
        } catch (RuntimeException e) {
            LOG.debug("metrics count failed", e);
        }
    }

    private static ErrorType classify(final Throwable e) {
        if (e instanceof ScriptCompilationException) {
            return ErrorType.SCRIPT_COMPILATION;
        }
        if (e instanceof ScriptSandboxException) {
            return ErrorType.SCRIPT_SANDBOX;
        }
        if (e instanceof ScriptTimeoutException) {
            return ErrorType.SCRIPT_TIMEOUT;
        }
        if (e instanceof ScriptExecutionException) {
            return ErrorType.SCRIPT_EXECUTION;
        }
        if (e instanceof NodeExecutionException) {
            return ErrorType.NODE_EXECUTION;
        }
        return ErrorType.UNKNOWN;
    }
}
