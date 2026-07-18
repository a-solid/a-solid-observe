package com.imsw.observe.pipeline.infrastructure.persistence;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.execution.model.ErrorType;
import com.imsw.observe.kernel.execution.spi.ExecutionRecorder;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

public final class JpaExecutionRecorder implements ExecutionRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(JpaExecutionRecorder.class);

    private static final int MAX_ERROR_MESSAGE = 4000;

    private final ExecutionRepository executionRepository;

    private final FailedExecutionRepository failedExecutionRepository;

    private final ObjectMapper objectMapper;

    private final SnowflakeIdGenerator snowflake;

    public JpaExecutionRecorder(
            final ExecutionRepository executionRepository,
            final FailedExecutionRepository failedExecutionRepository,
            final ObjectMapper objectMapper,
            final SnowflakeIdGenerator snowflake) {
        this.executionRepository = executionRepository;
        this.failedExecutionRepository = failedExecutionRepository;
        this.objectMapper = objectMapper;
        this.snowflake = snowflake;
    }

    @Override
    public void recordSuccess(
            final ExecutionContext ctx,
            final String outcome,
            final Duration duration,
            final boolean emittedAlert,
            final double sampleRatio) {
        if (!emittedAlert && !shouldSample(sampleRatio)) {
            return;
        }
        ExecutionMeta meta = ctx.meta();
        ExecutionPo po = new ExecutionPo();
        po.id = snowflake.next();
        po.namespace = meta.namespace();
        po.pipelineId = meta.pipelineId();
        po.pipelineVersion = meta.pipelineVersion();
        po.team = meta.team();
        po.application = meta.application();
        po.triggerType = (meta.triggerType() == null
                        ? com.imsw.observe.kernel.event.model.SourceType.UNKNOWN
                        : meta.triggerType())
                .name();
        po.triggerEvent = serializeEvent(meta.triggerEvent());
        po.subscriptionId = meta.subscriptionId();
        po.status = outcome;
        po.startedAt = meta.triggeredAt();
        po.endedAt = Instant.now();
        po.durationMs = duration.toMillis();
        po.traceId = meta.traceId();
        po.createdAt = Instant.now();
        try {
            executionRepository.save(po);
        } catch (RuntimeException e) {
            LOG.warn("failed to persist execution {} for pipeline {}", po.id, po.pipelineId, e);
        }
    }

    @Override
    public void recordFailure(
            final ExecutionContext ctx,
            final Throwable error,
            final Duration duration,
            final String nodeName,
            final ErrorType errorType) {
        ExecutionMeta meta = ctx.meta();
        FailedExecutionPo po = new FailedExecutionPo();
        po.id = snowflake.next();
        po.namespace = meta.namespace();
        po.pipelineId = meta.pipelineId();
        po.pipelineVersion = meta.pipelineVersion();
        po.executionId = meta.executionId();
        po.team = meta.team();
        po.application = meta.application();
        po.triggerType = (meta.triggerType() == null
                        ? com.imsw.observe.kernel.event.model.SourceType.UNKNOWN
                        : meta.triggerType())
                .name();
        po.triggerEvent = serializeEvent(meta.triggerEvent());
        po.subscriptionId = meta.subscriptionId();
        po.nodeName = nodeName;
        po.errorType = errorType.name();
        po.errorMessage = truncate(error == null ? null : error.getMessage(), MAX_ERROR_MESSAGE);
        po.stackTrace = truncate(toStackTrace(error), 32 * 1024);
        po.status = "PENDING";
        po.createdAt = Instant.now();
        try {
            failedExecutionRepository.save(po);
        } catch (RuntimeException e) {
            LOG.warn("failed to persist failed execution {} for pipeline {}", po.id, po.pipelineId, e);
        }
    }

    private String serializeEvent(final Object event) {
        if (event == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            LOG.warn("failed to serialize trigger event", e);
            return null;
        }
    }

    private static boolean shouldSample(final double sampleRatio) {
        if (sampleRatio <= 0.0) {
            return false;
        }
        if (sampleRatio >= 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < sampleRatio;
    }

    private static String truncate(final String value, final int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String toStackTrace(final Throwable error) {
        if (error == null) {
            return null;
        }
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
