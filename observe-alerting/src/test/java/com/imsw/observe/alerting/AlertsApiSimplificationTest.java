package com.imsw.observe.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.imsw.observe.alerting.infrastructure.alert.DefaultAlertsApi;
import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.Severity;
import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.CdcMeta;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.ExecutionData;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.event.model.SourceType;

/**
 * B9 / spec §5.5：AlertsApi Groovy 简化 API（critical/warning/info(annotations)）契约验证。
 *
 * <p>三 default 方法只接 annotations、labels 传 null，构造 AlertSpec 后调用 {@code emit(spec)}。
 * {@code DefaultAlertsApi.emit} 把 spec 转 AlertSignal 并 ctx.emitAlert（labels=null 归一化为 {@code Map.of()}）。
 *
 * <p>注意：label 打底合并（pipeline.labels + signal.labels）发生在 {@code DefaultAlertSink.persist}，
 * 不在 API 层；本测试用 mock ctx 仅断言 API 产出的 AlertSignal shape（severity + annotations，labels 为空）。
 * 打底合并由 {@code DefaultAlertSinkIntegrationTest.mergesPipelineAndScriptLabelsAndProjectsLabelDimensions}
 * 覆盖。DryRunAlertsApi 不做打底（dry-run 无 pipeline.labels 上下文），与 DefaultAlertsApi 一致地把 null 归一化为
 * empty——简化 API 在两条路径下行为一致。
 */
class AlertsApiSimplificationTest {

    @Test
    void criticalProducesCriticalSeveritySignalWithAnnotations() {
        TestExecutionContext ctx = newContext();
        DefaultAlertsApi api = new DefaultAlertsApi(ctx);

        api.critical(Map.of("summary", "fraud"));

        assertThat(ctx.data().alerts).hasSize(1);
        AlertSignal signal = ctx.data().alerts.get(0);
        assertThat(signal.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(signal.annotations()).containsEntry("summary", "fraud");
        // 简化 API 不传 labels → spec.labels=null → toSignal 归一化为 empty（打底在 sink 做）
        assertThat(signal.labels()).isEmpty();
        assertThat(signal.fingerprint()).isNotNull(); // 由 pipelineId + 空 labels 计算
        assertThat(signal.shortCircuit()).isFalse();
        assertThat(signal.ttl()).isNull();
    }

    @Test
    void warningProducesWarningSeveritySignal() {
        TestExecutionContext ctx = newContext();
        DefaultAlertsApi api = new DefaultAlertsApi(ctx);

        api.warning(Map.of("summary", "degraded"));

        AlertSignal signal = ctx.data().alerts.get(0);
        assertThat(signal.severity()).isEqualTo(Severity.WARNING);
        assertThat(signal.annotations()).containsEntry("summary", "degraded");
        assertThat(signal.labels()).isEmpty();
    }

    @Test
    void infoProducesInfoSeveritySignal() {
        TestExecutionContext ctx = newContext();
        DefaultAlertsApi api = new DefaultAlertsApi(ctx);

        api.info(Map.of("summary", "ok"));

        AlertSignal signal = ctx.data().alerts.get(0);
        assertThat(signal.severity()).isEqualTo(Severity.INFO);
        assertThat(signal.annotations()).containsEntry("summary", "ok");
        assertThat(signal.labels()).isEmpty();
    }

    /** 简化 API 不接 evidence/shortCircuit/ttl → spec 内全为 null/false，与逃生口 emit(AlertSpec) 等价。 */
    @Test
    void simplifiedApiDefaultsMatchEscapeHatchWithDefaults() {
        TestExecutionContext ctx = newContext();
        DefaultAlertsApi api = new DefaultAlertsApi(ctx);

        api.critical(Map.of("k", "v"));
        AlertSignal simplified = ctx.data().alerts.get(0);

        // 等价的逃生口写法（labels=null/shortCircuit=false/ttl=null/evidence=null）
        TestExecutionContext ctx2 = newContext();
        new DefaultAlertsApi(ctx2)
                .emit(new com.imsw.observe.kernel.alert.model.AlertSpec(
                        null, Severity.CRITICAL, null, Map.of("k", "v"), null, false, null));
        AlertSignal escapeHatch = ctx2.data().alerts.get(0);

        assertThat(simplified.severity()).isEqualTo(escapeHatch.severity());
        assertThat(simplified.annotations()).isEqualTo(escapeHatch.annotations());
        assertThat(simplified.labels()).isEqualTo(escapeHatch.labels());
        assertThat(simplified.fingerprint()).isEqualTo(escapeHatch.fingerprint());
    }

    private static TestExecutionContext newContext() {
        // ExecutionMeta.labels（pipeline 打底）非空；本测试不依赖打底（API 层不合并）。
        CdcMeta meta = new CdcMeta("t", "db", "tbl", Map.of());
        CdcEvent event = new CdcEvent(meta, Map.of(), Map.of("amount", 2000L), CdcOp.INSERT, Instant.now());
        ExecutionMeta execMeta = new ExecutionMeta(
                1001L,
                "trade",
                2001L,
                1,
                Map.of("team", "base"),
                null,
                null,
                SourceType.CDC,
                event,
                Instant.now(),
                3001L);
        return new TestExecutionContext(execMeta, new ExecutionData(event));
    }
}
