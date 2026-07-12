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
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionData;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.execution.model.ErrorType;
import com.imsw.observe.pipeline.infrastructure.script.DefaultExecutionContext;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestJpaFactory.class)
class JpaExecutionRecorderTest {

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private FailedExecutionRepository failedExecutionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private JpaExecutionRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new JpaExecutionRecorder(executionRepository, failedExecutionRepository, objectMapper);
        runInTx(() -> {
            executionRepository.deleteAll();
            failedExecutionRepository.deleteAll();
        });
    }

    @Test
    void successWithoutAlertAndZeroSampleWritesNothing() {
        runInTx(() -> recorder.recordSuccess(newContext(), "SUCCESS", Duration.ofMillis(5), false, 0.0));
        assertThat(executionRepository.count()).isZero();
        assertThat(failedExecutionRepository.count()).isZero();
    }

    @Test
    void successWithEmittedAlertForcesWrite() {
        runInTx(() -> recorder.recordSuccess(newContext(), "SUCCESS", Duration.ofMillis(5), true, 0.0));
        assertThat(executionRepository.count()).isEqualTo(1);
        ExecutionPo po = executionRepository.findAll().get(0);
        assertThat(po.status).isEqualTo("SUCCESS");
        assertThat(po.triggerType).isEqualTo("CDC");
        assertThat(po.triggerEvent).contains("orders");
    }

    @Test
    void failureWritesDeadLetterNotExecutions() {
        NodeExecutionException error = new NodeExecutionException("boom-node", "kaboom", new IllegalStateException());
        runInTx(() -> recorder.recordFailure(
                newContext(), error, Duration.ofMillis(7), "boom-node", ErrorType.NODE_EXECUTION));

        assertThat(executionRepository.count()).isZero();
        assertThat(failedExecutionRepository.count()).isEqualTo(1);
        var fe = failedExecutionRepository.findAll().get(0);
        assertThat(fe.errorType).isEqualTo("NODE_EXECUTION");
        assertThat(fe.nodeName).isEqualTo("boom-node");
        assertThat(fe.status).isEqualTo("PENDING");
        assertThat(fe.stackTrace).contains("kaboom");
    }

    private DefaultExecutionContext newContext() {
        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "t", "db", "orders", Map.of());
        Event event = new Event(meta, Map.of(), Map.of("amount", 2000L), Op.INSERT, Instant.now());
        ExecutionMeta execMeta = new ExecutionMeta(
                "exec-1",
                "demo-pipeline",
                1,
                "team",
                "app",
                Map.of("domain", "trade"),
                null,
                null,
                SourceType.CDC,
                event,
                Instant.now(),
                "sub-1");
        return new DefaultExecutionContext(execMeta, new ExecutionData(event));
    }

    private void runInTx(final Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }
}
