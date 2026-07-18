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
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionData;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.event.model.Op;
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

    @BeforeEach
    void cleanTables() {
        runInTx(() -> {
            evidenceRepository.deleteAll();
            alertRepository.deleteAll();
        });
    }

    @Test
    void emitsPersistsAndDedupsAlert() {
        DefaultAlertSink sink = new DefaultAlertSink(
                alertRepository,
                evidenceRepository,
                new EvidenceCollector(objectMapper),
                new AnnotationRenderer(),
                SNOWFLAKE);

        runInTx(() -> sink.drainAndPersist(newContext(alertSignal("fp-1", null))));
        runInTx(() -> sink.drainAndPersist(newContext(alertSignal("fp-1", null))));

        List<AlertPo> all = alertRepository.findAll();
        assertThat(all).hasSize(1);
        AlertPo alert = all.get(0);
        assertThat(alert.status).isEqualTo("FIRING");
        assertThat(alert.fingerprint).isEqualTo("fp-1");
        assertThat(alert.dedupCount).isEqualTo(2);
        assertThat(evidenceRepository.findAll()).hasSize(1);
    }

    @Test
    void resolveJobFlipsExpiredFiringToResolved() {
        DefaultAlertSink sink = new DefaultAlertSink(
                alertRepository,
                evidenceRepository,
                new EvidenceCollector(objectMapper),
                new AnnotationRenderer(),
                SNOWFLAKE);
        AlertResolveJob resolveJob = new AlertResolveJob(alertRepository);

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
        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "t", "db", "tbl", Map.of());
        Event event = new Event(meta, Map.of(), Map.of("amount", 2000L), Op.INSERT, Instant.now());
        ExecutionMeta execMeta = new ExecutionMeta(
                1001L,
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
