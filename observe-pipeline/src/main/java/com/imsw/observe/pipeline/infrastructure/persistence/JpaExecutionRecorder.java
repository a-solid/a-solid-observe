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
        po.pipelineId = toLong(meta.pipelineId());
        po.pipelineVersion = meta.pipelineVersion();
        po.team = meta.team();
        po.application = meta.application();
        po.triggerType =
                meta.triggerType() == null ? "UNKNOWN" : meta.triggerType().name();
        po.triggerEvent = serializeEvent(meta.triggerEvent());
        po.subscriptionId = toLong(meta.subscriptionId());
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
        po.pipelineId = toLong(meta.pipelineId());
        po.pipelineVersion = meta.pipelineVersion();
        po.executionId = toLong(meta.executionId());
        po.team = meta.team();
        po.application = meta.application();
        po.triggerType =
                meta.triggerType() == null ? "UNKNOWN" : meta.triggerType().name();
        po.triggerEvent = serializeEvent(meta.triggerEvent());
        po.subscriptionId = toLong(meta.subscriptionId());
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

    /**
     * 运行态 id 已迁 BIGINT（ADR-0003），而 {@link ExecutionMeta}（kernel）仍以 String 透传 snowflake id
     * 的字符串形式（"trace 关联用 BIGINT id 的字符串形式"）。PO 落库前在此做 String→Long 边界转换；
     * null 或非数字字符串视为 null（宽松，避免单条记录写入失败影响主流程）。
     */
    private static Long toLong(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
