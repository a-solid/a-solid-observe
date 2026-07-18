package com.imsw.observe.pipeline.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.spi.AlertSink;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.execution.spi.ExecutionRecorder;
import com.imsw.observe.kernel.script.spi.DbApi;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.infrastructure.engine.DefaultPipelineRunner;
import com.imsw.observe.pipeline.infrastructure.engine.LinearPipelineExecutor;
import com.imsw.observe.pipeline.infrastructure.engine.ScriptNode;
import com.imsw.observe.pipeline.infrastructure.script.DryRunAlertsApi;
import com.imsw.observe.pipeline.infrastructure.script.GroovyScriptEngineImpl;
import com.imsw.observe.pipeline.infrastructure.transaction.DryRunTransactionOperator;

/**
 * dry-run：用模拟 event 真跑一遍 pipeline，复用生产 DataSource + 事务，事务强制回滚。
 * db.* 真查真执行但回滚，告警由 CapturingSink 截获返回前端，DB 零副作用。
 */
@Service
public final class DryRunService {

    private final PlatformTransactionManager transactionManager;

    private final DbApi dbApi;

    private final SnowflakeIdGenerator snowflake;

    public DryRunService(
            final PlatformTransactionManager transactionManager,
            final DbApi dbApi,
            final SnowflakeIdGenerator snowflake) {
        this.transactionManager = transactionManager;
        this.dbApi = dbApi;
        this.snowflake = snowflake;
    }

    public DryRunResult run(final Pipeline pipeline, final Event event) {
        CapturingSink sink = new CapturingSink();
        GroovyScriptEngineImpl engine = new GroovyScriptEngineImpl();
        LinearPipelineExecutor executor =
                new LinearPipelineExecutor(specNode -> new ScriptNode(engine, DryRunAlertsApi::new, () -> dbApi));
        DefaultPipelineRunner runner = new DefaultPipelineRunner(
                executor,
                sink,
                new DryRunTransactionOperator(transactionManager),
                NoopExecutionRecorder.INSTANCE,
                snowflake);
        String outcome;
        try {
            runner.run(pipeline, event, null);
            outcome = "SUCCESS";
        } catch (RuntimeException e) {
            outcome = "FAILED";
        }
        return new DryRunResult(outcome, sink.captured, Map.of());
    }

    public record DryRunResult(
            String outcome, List<AlertSignal> alerts, Map<String, Map<String, Object>> nodeOutputs) {}

    private static final class CapturingSink implements AlertSink {

        final List<AlertSignal> captured = new ArrayList<>();

        @Override
        public void drainAndPersist(final ExecutionContext ctx) {
            captured.addAll(ctx.data().drainNewAlerts());
        }
    }

    private static final class NoopExecutionRecorder implements ExecutionRecorder {

        static final NoopExecutionRecorder INSTANCE = new NoopExecutionRecorder();

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
}
