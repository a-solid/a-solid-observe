package com.imsw.observe.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertPo;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceRepository;
import com.imsw.observe.bootstrap.worker.source.InMemoryCdcSource;
import com.imsw.observe.config.application.PipelineCrudService;
import com.imsw.observe.config.application.PipelineHotReloader;
import com.imsw.observe.config.application.SubscriptionCrudService;
import com.imsw.observe.config.application.VersionPublishService;
import com.imsw.observe.config.domain.Subscription;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.domain.ErrorPolicy;
import com.imsw.observe.pipeline.domain.NodeSpec;
import com.imsw.observe.pipeline.domain.Pipeline;

@SpringBootTest(classes = ObserveApplication.class)
class EndToEndFlowTest {

    @Autowired
    private PipelineCrudService pipelines;

    @Autowired
    private VersionPublishService versions;

    @Autowired
    private SubscriptionCrudService subscriptions;

    @Autowired
    private PipelineRegistry registry;

    @Autowired
    private PipelineHotReloader hotReloader;

    @Autowired
    private InMemoryCdcSource cdcSource;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private EvidenceRepository evidenceRepository;

    @Test
    void cdcEventMatchesAndPersistsAlert() throws Exception {
        evidenceRepository.deleteAll();
        alertRepository.deleteAll();

        pipelines.create("e2e-pipeline", "team", "app", Map.of(), "E2E", "", "tester");
        Pipeline pipeline = buildPipeline("e2e-pipeline", 1);
        versions.saveDraft(pipeline, "tester");
        versions.publish("e2e-pipeline", 1, "tester");

        Subscription sub = new Subscription(
                "e2e-sub",
                "e2e-pipeline",
                1,
                "mq",
                "topic",
                "trade_db",
                "orders",
                Set.of(Op.INSERT),
                SourceType.CDC,
                null,
                Subscription.ActionType.RUN,
                null,
                null,
                null,
                null,
                Subscription.Status.ACTIVE,
                null,
                null,
                null);
        subscriptions.create(sub);

        hotReloader.refresh();
        assertThat(registry.isLoaded()).isTrue();

        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "mq", "trade_db", "orders", Map.of());
        Event event = new Event(meta, Map.of(), Map.of("amount", 5000L), Op.INSERT, Instant.now());
        cdcSource.push(List.of(event));

        AlertPo alert = waitForFirstAlert();
        assertThat(alert.status).isEqualTo("FIRING");
        assertThat(alert.severity).isEqualTo("CRITICAL");
        assertThat(evidenceRepository.findAll()).hasSize(1);
    }

    private AlertPo waitForFirstAlert() throws Exception {
        for (int i = 0; i < 50; i++) {
            if (!alertRepository.findAll().isEmpty()) {
                return alertRepository.findAll().get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no alert persisted after 5s");
    }

    private static Pipeline buildPipeline(final String id, final int version) {
        NodeSpec node = new NodeSpec(
                "check",
                """
                def amt = event.after.get("amount") as Long
                if (amt != null && amt > 1000) {
                    alerts.emit(
                        com.imsw.observe.kernel.alert.model.Severity.CRITICAL,
                        java.util.Map.of("entity", "order"),
                        java.util.Map.of("summary", "big"),
                        new com.imsw.observe.kernel.alert.model.AlertSignal.EvidenceSpec(
                            java.util.List.of(), true, true))
                    return true
                }
                return false
                """,
                ErrorPolicy.FAIL,
                Set.of(),
                Set.of());
        return new Pipeline(
                id,
                version,
                "team",
                "app",
                Map.of(),
                "E2E",
                Pipeline.Status.PUBLISHED,
                List.of(node),
                Instant.now(),
                Instant.now(),
                1.0);
    }
}
