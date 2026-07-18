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
import com.imsw.observe.config.application.NamespaceCrudService;
import com.imsw.observe.config.application.PipelineCrudService;
import com.imsw.observe.config.application.PipelineHotReloader;
import com.imsw.observe.config.application.SubscriptionCrudService;
import com.imsw.observe.config.application.VersionPublishService;
import com.imsw.observe.config.domain.SubscriptionDefinition;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.domain.ErrorPolicy;
import com.imsw.observe.pipeline.domain.NodeSpec;
import com.imsw.observe.pipeline.domain.Pipeline;

@SpringBootTest(classes = ObserveApplication.class)
class EndToEndFlowTest {

    private static final String NAMESPACE = "e2e";

    private static final String PIPELINE_NAME = "E2E";

    @Autowired
    private NamespaceCrudService namespaces;

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

        // 软隔离铁律 (ADR-0002)：必须先建 namespace，否则 pipeline/subscription create 被拒。
        if (namespaces.findByName(NAMESPACE) == null) {
            namespaces.create(NAMESPACE, "End-to-End smoke");
        }

        Long pipelineId = pipelines
                .create(NAMESPACE, PIPELINE_NAME, "team", "app", Map.of(), "", "tester")
                .id();
        Pipeline pipeline = buildPipeline(pipelineId, 1);
        versions.saveDraft(NAMESPACE, PIPELINE_NAME, pipeline, "tester");
        versions.publish(NAMESPACE, PIPELINE_NAME, 1, "tester");

        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                NAMESPACE,
                pipelineId,
                1,
                "mq",
                "topic",
                "trade_db",
                "orders",
                Set.of(Op.INSERT),
                SourceType.CDC,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                "e2e-sub",
                null,
                SubscriptionDefinition.Status.ACTIVE,
                "tester",
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
        // namespace 软隔离端到端证明：落库告警的 namespace 必须等于触发链路的 namespace。
        assertThat(alert.namespace).isEqualTo(NAMESPACE);
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

    private static Pipeline buildPipeline(final Long id, final int version) {
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
        // 运行期 Pipeline 的 namespace 必须与持久化 PO 一致（loader 用 PO.namespace 覆盖）。
        return new Pipeline(
                id,
                NAMESPACE,
                version,
                "team",
                "app",
                Map.of(),
                PIPELINE_NAME,
                Pipeline.Status.PUBLISHED,
                List.of(node),
                Instant.now(),
                Instant.now(),
                1.0);
    }
}
