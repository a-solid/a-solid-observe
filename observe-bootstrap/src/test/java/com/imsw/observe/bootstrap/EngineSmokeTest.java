package com.imsw.observe.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.imsw.observe.alerting.infrastructure.alert.DefaultAlertsApi;
import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.Severity;
import com.imsw.observe.kernel.alert.spi.AlertSink;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.execution.model.ErrorType;
import com.imsw.observe.kernel.execution.spi.ExecutionRecorder;
import com.imsw.observe.kernel.script.spi.DbApi;
import com.imsw.observe.kernel.script.spi.GroovyScriptEngine;
import com.imsw.observe.kernel.transaction.spi.TransactionOperator;
import com.imsw.observe.pipeline.application.PipelineExecutor;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.domain.ErrorPolicy;
import com.imsw.observe.pipeline.domain.NodeSpec;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.infrastructure.engine.DefaultPipelineRunner;
import com.imsw.observe.pipeline.infrastructure.engine.LinearPipelineExecutor;
import com.imsw.observe.pipeline.infrastructure.engine.ScriptNode;
import com.imsw.observe.pipeline.infrastructure.script.GroovyScriptEngineImpl;

/**
 * 无 Spring、无 DB 的引擎冒烟测试：手动装配 Groovy 引擎 + 线性执行器 + Runner + Noop 协作者，
 * 验证 Groovy 脚本节点 → ctx 传递 → alerts.emit → AlertSink 收集 → short-circuit 闭环。
 *
 * <p>承接被删除的 demo（DemoMain/DemoPipelineFactory）的验证价值，符合 CONTEXT.md Conventions
 * "不在 production 代码里留 demo / 手动 main 入口；引擎冒烟验证由测试承接"。
 */
class EngineSmokeTest {

    @Test
    void underLimitDoesNotEmitAndDoesNotShortCircuit() {
        CapturingAlertSink sink = new CapturingAlertSink();
        PipelineRunner runner = newRunner(sink);
        Pipeline pipeline = buildPipeline();
        Event event = mockEvent(2000L);

        runner.run(pipeline, event, null);

        assertThat(sink.captured).isEmpty();
    }

    @Test
    void overLimitEmitsCriticalAlert() {
        CapturingAlertSink sink = new CapturingAlertSink();
        PipelineRunner runner = newRunner(sink);
        Pipeline pipeline = buildPipeline();
        Event event = mockEvent(20000L);

        runner.run(pipeline, event, null);

        assertThat(sink.captured).hasSize(1);
        AlertSignal signal = sink.captured.get(0);
        assertThat(signal.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(signal.labels()).containsEntry("entity", "order");
        // DefaultAlertsApi 未传 fingerprint 时由 pipelineId+labels 计算，非空
        assertThat(signal.fingerprint()).isNotBlank();
    }

    private static PipelineRunner newRunner(final AlertSink sink) {
        GroovyScriptEngine engine = new GroovyScriptEngineImpl();
        DbApi dbApi = new NoopDbApi();
        PipelineExecutor executor = new LinearPipelineExecutor(
                spec -> new ScriptNode(engine, ctx -> new DefaultAlertsApi(ctx), () -> dbApi));
        return new DefaultPipelineRunner(
                executor,
                sink,
                new NoopTransactionOperator(),
                new NoopExecutionRecorder(),
                new com.imsw.observe.kernel.util.SnowflakeIdGenerator(1L, 0L));
    }

    private static Pipeline buildPipeline() {
        NodeSpec compute = new NodeSpec(
                "compute",
                """
                def raw = event.after.get("amount")
                def amt = raw as BigDecimal
                ctx.set("amt", amt)
                return false
                """,
                ErrorPolicy.FAIL,
                Set.of("amt"),
                Set.of());
        NodeSpec check = new NodeSpec(
                "check",
                """
                def amt = ctx.get("amt", BigDecimal)
                def limit = 10000
                if (amt != null && amt > limit) {
                    alerts.emit(
                        com.imsw.observe.kernel.alert.model.Severity.CRITICAL,
                        java.util.Map.of("entity", "order", "team", "smoke"),
                        java.util.Map.of("summary", "fraud amt=" + amt.toString()),
                        new com.imsw.observe.kernel.alert.model.AlertSignal.EvidenceSpec(
                            java.util.List.of(),
                            true,
                            true
                        )
                    )
                    return true
                }
                return false
                """,
                ErrorPolicy.FAIL,
                Set.of(),
                Set.of("amt"));
        return new Pipeline(
                1L,
                1,
                "smoke-team",
                "smoke-app",
                Map.of("domain", "trade"),
                "Smoke Pipeline",
                Pipeline.Status.PUBLISHED,
                List.of(compute, check),
                Instant.now(),
                Instant.now(),
                1.0);
    }

    private static Event mockEvent(final long amount) {
        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "smoke-mq", "test_db", "orders", Map.of());
        return new Event(meta, Map.of(), Map.of("amount", amount), Op.INSERT, Instant.now());
    }

    /** 收集型 AlertSink：把 ctx 里 emit 的告警收集起来供断言（区别于 demo 的丢弃型）。 */
    private static final class CapturingAlertSink implements AlertSink {
        final List<AlertSignal> captured = new ArrayList<>();

        @Override
        public void drainAndPersist(final ExecutionContext ctx) {
            captured.addAll(ctx.data().drainNewAlerts());
        }
    }

    private static final class NoopTransactionOperator implements TransactionOperator {
        @Override
        public void execute(final TransactionCallback action) {
            action.run();
        }
    }

    private static final class NoopExecutionRecorder implements ExecutionRecorder {
        @Override
        public void recordSuccess(
                final ExecutionContext ctx,
                final String outcome,
                final java.time.Duration duration,
                final boolean emittedAlert,
                final double sampleRatio) {}

        @Override
        public void recordFailure(
                final ExecutionContext ctx,
                final Throwable error,
                final java.time.Duration duration,
                final String nodeName,
                final ErrorType errorType) {}
    }

    private static final class NoopDbApi implements DbApi {
        @Override
        public Map<String, Object> queryOne(final String sql, final Map<String, Object> params) {
            return null;
        }

        @Override
        public List<Map<String, Object>> queryAll(final String sql, final Map<String, Object> params) {
            return List.of();
        }

        @Override
        public int update(final String sql, final Map<String, Object> params) {
            return 0;
        }

        @Override
        public List<Map<String, Object>> call(final String spName, final Map<String, Object> params) {
            return List.of();
        }
    }
}
