package com.imsw.observe.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import com.imsw.observe.alerting.infrastructure.AlertResolveJob;
import com.imsw.observe.alerting.infrastructure.DefaultAlertSink;
import com.imsw.observe.alerting.infrastructure.evidence.AnnotationRenderer;
import com.imsw.observe.alerting.infrastructure.evidence.EvidenceCollector;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertPo;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceRepository;
import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.Severity;
import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.CdcMeta;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.ExecutionData;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestJpaFactory.class)
class DefaultAlertSinkIntegrationTest {

    private static final SnowflakeIdGenerator SNOWFLAKE = new SnowflakeIdGenerator(1L, 0L);

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private EvidenceRepository evidenceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilenceRepository silenceRepository;

    /** 构造一个接真实 silence repo 的 sink（无规则时 matcher 不命中）。 */
    private DefaultAlertSink newSink() {
        return new DefaultAlertSink(
                alertRepository,
                evidenceRepository,
                new EvidenceCollector(objectMapper),
                new AnnotationRenderer(),
                new com.imsw.observe.alerting.infrastructure.AlertSilenceMatcher(
                        silenceRepository, java.time.Duration.ofMillis(1000)),
                SNOWFLAKE);
    }

    @BeforeEach
    void cleanTables() {
        runInTx(() -> {
            evidenceRepository.deleteAll();
            alertRepository.deleteAll();
        });
    }

    @Test
    void emitsPersistsAndDedupsAlert() {
        DefaultAlertSink sink = newSink();

        runInTx(() -> sink.drainAndPersist(newContext(alertSignal("fp-1", null))));
        runInTx(() -> sink.drainAndPersist(newContext(alertSignal("fp-1", null))));

        List<AlertPo> all = alertRepository.findAll();
        assertThat(all).hasSize(1);
        AlertPo alert = all.get(0);
        assertThat(alert.status).isEqualTo("FIRING");
        assertThat(alert.fingerprint).isEqualTo("fp-1");
        assertThat(alert.namespace).isEqualTo("trade"); // 告警继承执行上下文的 namespace (ADR-0002)
        assertThat(alert.dedupCount).isEqualTo(2);
        // ADR-0005 §2：1:N —— 每次 emit（含 dedup 命中）各写一条证据
        assertThat(evidenceRepository.findAll()).hasSize(2);
    }

    @Test
    void resolveJobFlipsExpiredFiringToResolved() {
        DefaultAlertSink sink = newSink();
        AlertResolveJob resolveJob = new AlertResolveJob(alertRepository, 1000);

        runInTx(() -> sink.drainAndPersist(newContext(alertSignal("fp-2", Duration.ofMillis(1)))));

        AlertPo alert = alertRepository.findAll().get(0);
        assertThat(alert.status).isEqualTo("FIRING");

        sleep(20);
        int resolved = runInTxReturning(() -> resolveJob.resolveExpiredAlerts());
        assertThat(resolved).isEqualTo(1);

        AlertPo refreshed = alertRepository.findById(alert.id).orElseThrow();
        assertThat(refreshed.status).isEqualTo("RESOLVED");
        assertThat(refreshed.resolvedAt).isNotNull();
    }

    private TestExecutionContext newContext(final AlertSignal signal) {
        // ADR-0006：触发事件为 CdcEvent（triggerType 仍用 SourceType.CDC）。
        CdcMeta meta = new CdcMeta("t", "db", "tbl", Map.of());
        CdcEvent event = new CdcEvent(meta, Map.of(), Map.of("amount", 2000L), CdcOp.INSERT, Instant.now());
        ExecutionMeta execMeta = new ExecutionMeta(
                1001L,
                "trade",
                2001L,
                1,
                "team",
                "app",
                Map.of("domain", "trade"),
                null,
                null,
                SourceType.CDC,
                event,
                Instant.now(),
                3001L);
        ExecutionData data = new ExecutionData(event);
        TestExecutionContext ctx = new TestExecutionContext(execMeta, data);
        ctx.putNodeOutput("check", "amt", new java.math.BigDecimal("2000"));
        ctx.emitAlert(signal);
        return ctx;
    }

    private static AlertSignal alertSignal(final String fingerprint, final Duration ttl) {
        return new AlertSignal(
                fingerprint,
                Severity.CRITICAL,
                Map.of("entity", "order", "team", "demo"),
                Map.of("summary", "fraud"),
                new AlertSignal.EvidenceSpec(List.of(), true, true),
                false,
                ttl);
    }

    private void runInTx(final Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    private int runInTxReturning(final java.util.function.IntSupplier action) {
        Integer result = new TransactionTemplate(transactionManager).execute(status -> action.getAsInt());
        return result == null ? 0 : result;
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
