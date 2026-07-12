package com.imsw.observe.bootstrap.demo;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.alerting.infrastructure.alert.DefaultAlertsApi;
import com.imsw.observe.kernel.alert.spi.AlertSink;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.execution.spi.ExecutionRecorder;
import com.imsw.observe.kernel.script.spi.DbApi;
import com.imsw.observe.kernel.script.spi.GroovyScriptEngine;
import com.imsw.observe.kernel.transaction.spi.TransactionOperator;
import com.imsw.observe.pipeline.application.PipelineExecutor;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.infrastructure.engine.DefaultPipelineRunner;
import com.imsw.observe.pipeline.infrastructure.engine.LinearPipelineExecutor;
import com.imsw.observe.pipeline.infrastructure.engine.ScriptNode;
import com.imsw.observe.pipeline.infrastructure.script.GroovyScriptEngineImpl;

/**
 * 独立 main 入口的 demo（无 Spring、无 DB）。所有占位实现内嵌，自给自足。
 * demo 的 Noop 不复用 pipeline/bootstrap 的（那些是 dry-run 或生产用，语义不同）。
 */
public final class DemoMain {

    private static final Logger LOG = LoggerFactory.getLogger(DemoMain.class);

    private DemoMain() {}

    public static void main(final String[] args) {
        GroovyScriptEngine engine = new GroovyScriptEngineImpl();
        AlertSink alertSink = new DemoAlertSink();
        TransactionOperator transactionOperator = new DemoTransactionOperator();
        ExecutionRecorder executionRecorder = new DemoExecutionRecorder();
        DbApi dbApi = new DemoDbApi();

        PipelineExecutor executor = new LinearPipelineExecutor(
                spec -> new ScriptNode(engine, ctx -> new DefaultAlertsApi(ctx, "demo-pipeline"), () -> dbApi));

        PipelineRunner runner = new DefaultPipelineRunner(executor, alertSink, transactionOperator, executionRecorder);

        Pipeline pipeline = DemoPipelineFactory.buildPipeline();
        Event event = DemoPipelineFactory.mockEvent();

        LOG.info("==== demo start ====");
        runner.run(pipeline, event, null);
        LOG.info("==== demo end ====");
    }

    /** demo 告警 sink：丢弃（demo 不落库）。 */
    private static final class DemoAlertSink implements AlertSink {
        @Override
        public void drainAndPersist(final ExecutionContext ctx) {
            ctx.data().drainNewAlerts();
        }
    }

    /** demo 事务操作器：不开事务（demo 无 DB）。 */
    private static final class DemoTransactionOperator implements TransactionOperator {
        @Override
        public void execute(final TransactionCallback action) {
            action.run();
        }
    }

    /** demo 执行记录器：丢弃（demo 不记 execution）。 */
    private static final class DemoExecutionRecorder implements ExecutionRecorder {
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
                final com.imsw.observe.kernel.execution.model.ErrorType errorType) {}
    }

    /** demo DB API：返回空（demo 无 DB）。 */
    private static final class DemoDbApi implements DbApi {
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
