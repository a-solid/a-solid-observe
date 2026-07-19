package com.imsw.observe.pipeline.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imsw.observe.kernel.error.NodeExecutionException;
import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.CdcMeta;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.ExecutionData;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.execution.model.ErrorType;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;
import com.imsw.observe.pipeline.infrastructure.script.DefaultExecutionContext;

/**
 * 合表后 recorder：成功全量写行（trigger_event 按采样）、失败写同表 status=FAILED（含 trigger_event + stack）。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestJpaFactory.class)
class JpaExecutionRecorderTest {

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private JpaExecutionRecorder recorder;

    private final SnowflakeIdGenerator snowflake = new SnowflakeIdGenerator(1L, 0L);

    @BeforeEach
    void setUp() {
        recorder = new JpaExecutionRecorder(executionRepository, objectMapper, snowflake);
        runInTx(() -> executionRepository.deleteAll());
    }

    @Test
    void successWithoutAlertAndZeroSampleWritesRowWithNullTriggerEvent() {
        // 合表后：成功永远写行（success-rate 计数准）；采样只作用于 trigger_event——未 emit + 零采样 → null。
        runInTx(() -> recorder.recordSuccess(newContext(), "SUCCESS", Duration.ofMillis(5), false, 0.0));
        assertThat(executionRepository.count()).isEqualTo(1);
        ExecutionPo po = executionRepository.findAll().get(0);
        assertThat(po.status).isEqualTo("SUCCESS");
        assertThat(po.triggerEvent).isNull();
    }

    @Test
    void successWithEmittedAlertWritesTriggerEvent() {
        runInTx(() -> recorder.recordSuccess(newContext(), "SUCCESS", Duration.ofMillis(5), true, 0.0));
        assertThat(executionRepository.count()).isEqualTo(1);
        ExecutionPo po = executionRepository.findAll().get(0);
        assertThat(po.id).isPositive(); // snowflake-allocated BIGINT id (ADR-0003)
        assertThat(po.namespace).isEqualTo("trade"); // namespace 软隔离维度透传 (ADR-0002)
        assertThat(po.pipelineId).isEqualTo(2001L);
        assertThat(po.subscriptionId).isEqualTo(3001L);
        assertThat(po.status).isEqualTo("SUCCESS");
        assertThat(po.triggerType).isEqualTo("CDC");
        assertThat(po.triggerEvent).contains("orders");
    }

    @Test
    void failureWritesSameTableAsFailed() {
        NodeExecutionException error = new NodeExecutionException("boom-node", "kaboom", new IllegalStateException());
        runInTx(() -> recorder.recordFailure(
                newContext(), error, Duration.ofMillis(7), "boom-node", ErrorType.NODE_EXECUTION));

        assertThat(executionRepository.count()).isEqualTo(1);
        ExecutionPo po = executionRepository.findAll().get(0);
        assertThat(po.id).isPositive();
        assertThat(po.namespace).isEqualTo("trade");
        assertThat(po.executionId).isEqualTo(1001L);
        assertThat(po.pipelineId).isEqualTo(2001L);
        assertThat(po.subscriptionId).isEqualTo(3001L);
        assertThat(po.status).isEqualTo("FAILED");
        assertThat(po.errorType).isEqualTo("NODE_EXECUTION");
        assertThat(po.nodeName).isEqualTo("boom-node");
        assertThat(po.stackTrace).contains("kaboom");
        assertThat(po.triggerEvent).contains("orders"); // 失败 trigger_event 全量写（排错刚需）
    }

    private DefaultExecutionContext newContext() {
        // ADR-0006：触发事件为 CdcEvent（triggerType 仍用 SourceType.CDC 表达执行链路来源）。
        // B9 / ADR-0004：ExecutionMeta 只携带 pipeline.labels（team/application 一等字段已移除）。
        CdcMeta meta = new CdcMeta("t", "db", "orders", Map.of());
        CdcEvent event = new CdcEvent(meta, Map.of(), Map.of("amount", 2000L), CdcOp.INSERT, Instant.now());
        ExecutionMeta execMeta = new ExecutionMeta(
                1001L,
                "trade",
                2001L,
                1,
                Map.of("domain", "trade"),
                null,
                null,
                SourceType.CDC,
                event,
                Instant.now(),
                3001L);
        return new DefaultExecutionContext(execMeta, new ExecutionData(event));
    }

    private void runInTx(final Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }
}
