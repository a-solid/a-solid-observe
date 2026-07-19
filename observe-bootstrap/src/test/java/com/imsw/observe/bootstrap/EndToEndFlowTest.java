package com.imsw.observe.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.CdcMeta;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.CronSource;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.domain.ErrorPolicy;
import com.imsw.observe.pipeline.domain.NodeSpec;
import com.imsw.observe.pipeline.domain.Pipeline;

@SpringBootTest(classes = ObserveApplication.class)
class EndToEndFlowTest {

    private static final String NAMESPACE = "e2e";

    private static final String PIPELINE_NAME = "E2E";

    private static final String CRON_PIPELINE_NAME = "E2E_CRON";

    private static final String CRON_NAME = "e2e-every-second";

    private static final String SCHEDULE_PIPELINE_NAME = "E2E_SCHEDULE";

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
    private CronSource cronScheduler;

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
                .create(NAMESPACE, PIPELINE_NAME, Map.of("team", "team", "app", "app"), "", "tester")
                .id();
        Pipeline pipeline = buildPipeline(pipelineId, 1);
        versions.saveDraft(NAMESPACE, PIPELINE_NAME, pipeline, "tester");
        versions.publish(NAMESPACE, PIPELINE_NAME, 1, "tester");

        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                NAMESPACE,
                java.util.List.of(pipelineId),
                "mq",
                "topic",
                "trade_db",
                "orders",
                Set.of(CdcOp.INSERT),
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
                null,
                null,
                null,
                null);
        subscriptions.create(sub);

        hotReloader.refresh();
        assertThat(registry.isLoaded()).isTrue();

        // ADR-0006 keystone：CDC→CdcEvent→match→pipeline→alert 全链路用 sealed Event 子类型走通。
        // CdcMeta 不再带 sourceType（由 CdcEvent 子类型隐式 = CDC）；source 仍为 CDC 通道标识。
        CdcMeta meta = new CdcMeta("mq", "trade_db", "orders", Map.of());
        Event event = new CdcEvent(meta, Map.of(), Map.of("amount", 5000L), CdcOp.INSERT, Instant.now());
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

    /**
     * CRON 订阅端到端路由（ADR-0007 B4 P3 验证）：建 namespace + pipeline + 一条 {@code "* * * * * ?"}
     * 每秒触发的 CRON 订阅，热加载（触发 {@link CronSource#sync}），等待真实 fire 落库告警。
     *
     * <p>覆盖 P3 路由 bug 修正：TickEvent.meta().source() 必须等于订阅在 subscriptionsBySource 的索引键，
     * 否则 matcher 查不到、TickEvent 永不路由、pipeline 不执行、告警不落库。
     */
    @Test
    void cronSubscriptionFiresAndPersistsAlert() throws Exception {
        evidenceRepository.deleteAll();
        alertRepository.deleteAll();

        if (namespaces.findByName(NAMESPACE) == null) {
            namespaces.create(NAMESPACE, "End-to-End smoke");
        }

        // 一条无 payload 触发型 pipeline：TickEvent 无 after/before，脚本里无条件 emit（用于验证 cron 路由）。
        Long pipelineId = pipelines
                .create(NAMESPACE, CRON_PIPELINE_NAME, Map.of("team", "team", "app", "app"), "", "tester")
                .id();
        Pipeline pipeline = buildCronPipeline(pipelineId, 1);
        versions.saveDraft(NAMESPACE, CRON_PIPELINE_NAME, pipeline, "tester");
        versions.publish(NAMESPACE, CRON_PIPELINE_NAME, 1, "tester");

        // CRON 订阅：mq = cronName（loader 把 mq 灌入 SourceRef.mq → 索引键），cron 表达式每秒。
        // mq/cronName 取同值与 B4 推荐路径一致；expression "* * * * * ?" = 每秒。
        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                NAMESPACE,
                java.util.List.of(pipelineId),
                CRON_NAME,
                null,
                null,
                null,
                Set.of(),
                SourceType.CRON,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                "e2e-cron-sub",
                null,
                SubscriptionDefinition.Status.ACTIVE,
                "tester",
                null,
                null,
                "* * * * * ?",
                CRON_NAME,
                SubscriptionDefinition.Concurrent.SKIP);
        subscriptions.create(sub);

        // 热加载后显式同步 CronSource——与生产 HotReloaderScheduler.refresh() 末尾的 sync 调用一致
        // （@Scheduled 30s 周期太慢，测试主动触发以保证确定性）。
        hotReloader.refresh();
        cronScheduler.sync(registry.snapshot());
        assertThat(registry.isLoaded()).isTrue();

        AlertPo alert = waitForFirstAlert();
        assertThat(alert.status).isEqualTo("FIRING");
        // namespace 软隔离：cron 触发链路同样落库到触发订阅所在 namespace。
        assertThat(alert.namespace).isEqualTo(NAMESPACE);

        // 清理：删除 CRON 订阅并重新 sync，让 CronSource 取消调度句柄——避免后续测试
        // （cdcEventMatchesAndPersistsAlert 共享同一 Spring 上下文/DB）期间 CRON 仍在每秒 fire
        // 污染 evidence 计数。
        subscriptions.delete(NAMESPACE, "e2e-cron-sub");
        hotReloader.refresh();
        cronScheduler.sync(registry.snapshot());
    }

    /**
     * SCHEDULE-only 端到端（delayed-redesign spec D1/D2）：CDC INSERT → schedule delay 500ms →
     * fire → DelayedEvent 重放 → matcher 绕过、订阅级扇出 → pipeline 跑 → 告警落库。
     *
     * <p>覆盖关键链路：
     * <ul>
     *   <li>订阅级 action 分发：dispatcher 不在 pipeline 内调 handler。</li>
     *   <li>namespace 级 key 拼装：fullKey = "e2e:{orderId}"。</li>
     *   <li>fire 走 relayer → dispatcher.relay → 绕 matcher 直扇出。</li>
     * </ul>
     */
    @Test
    void scheduleOnlyFiresAfterDelayAndPersistsAlert() throws Exception {
        evidenceRepository.deleteAll();
        alertRepository.deleteAll();

        if (namespaces.findByName(NAMESPACE) == null) {
            namespaces.create(NAMESPACE, "End-to-End smoke");
        }

        Long pipelineId = pipelines
                .create(NAMESPACE, SCHEDULE_PIPELINE_NAME, Map.of("team", "team", "app", "app"), "", "tester")
                .id();
        Pipeline pipeline = buildSchedulePipeline(pipelineId, 1);
        versions.saveDraft(NAMESPACE, SCHEDULE_PIPELINE_NAME, pipeline, "tester");
        versions.publish(NAMESPACE, SCHEDULE_PIPELINE_NAME, 1, "tester");

        // SCHEDULE 订阅：500ms 后 fire，correlationKeyPath=after.orderId。
        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                NAMESPACE,
                java.util.List.of(pipelineId),
                "mq",
                "topic",
                "trade_db",
                "orders",
                Set.of(CdcOp.INSERT),
                SourceType.CDC,
                null,
                SubscriptionDefinition.ActionType.SCHEDULE,
                Duration.ofMillis(500),
                "after.orderId",
                "e2e-schedule-sub",
                null,
                SubscriptionDefinition.Status.ACTIVE,
                "tester",
                null,
                null,
                null,
                null,
                null);
        subscriptions.create(sub);

        hotReloader.refresh();
        assertThat(registry.isLoaded()).isTrue();

        CdcMeta meta = new CdcMeta("mq", "trade_db", "orders", Map.of());
        Event event = new CdcEvent(meta, Map.of(), Map.of("orderId", "order-sch-1"), CdcOp.INSERT, Instant.now());
        cdcSource.push(List.of(event));

        AlertPo alert = waitForFirstAlert();
        assertThat(alert.status).isEqualTo("FIRING");
        assertThat(alert.namespace).isEqualTo(NAMESPACE);
        assertThat(evidenceRepository.findAll()).hasSize(1);
    }

    private static Pipeline buildSchedulePipeline(final Long id, final int version) {
        // pipeline 跑在 DelayedEvent 上下文里——脚本可无条件 emit（验证 replay 后 pipeline 真被执行）。
        NodeSpec node = new NodeSpec(
                "emit-on-fire",
                """
                alerts.emit(
                    com.imsw.observe.kernel.alert.model.Severity.CRITICAL,
                    java.util.Map.of("entity", "delayed"),
                    java.util.Map.of("summary", "fired"),
                    new com.imsw.observe.kernel.alert.model.AlertSignal.EvidenceSpec(
                        java.util.List.of(), true, true))
                return true
                """,
                ErrorPolicy.FAIL,
                Set.of(),
                Set.of());
        return new Pipeline(
                id,
                NAMESPACE,
                version,
                "team",
                "app",
                Map.of(),
                SCHEDULE_PIPELINE_NAME,
                Pipeline.Status.PUBLISHED,
                List.of(node),
                Instant.now(),
                Instant.now(),
                1.0);
    }

    private static Pipeline buildCronPipeline(final Long id, final int version) {
        // TickEvent 无 payload；脚本里直接 emit（用 always-true 守卫简化）。
        NodeSpec node = new NodeSpec(
                "emit",
                """
                alerts.emit(
                    com.imsw.observe.kernel.alert.model.Severity.CRITICAL,
                    java.util.Map.of("entity", "cron"),
                    java.util.Map.of("summary", "tick"),
                    new com.imsw.observe.kernel.alert.model.AlertSignal.EvidenceSpec(
                        java.util.List.of(), true, true))
                return true
                """,
                ErrorPolicy.FAIL,
                Set.of(),
                Set.of());
        return new Pipeline(
                id,
                NAMESPACE,
                version,
                "team",
                "app",
                Map.of(),
                CRON_PIPELINE_NAME,
                Pipeline.Status.PUBLISHED,
                List.of(node),
                Instant.now(),
                Instant.now(),
                1.0);
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
