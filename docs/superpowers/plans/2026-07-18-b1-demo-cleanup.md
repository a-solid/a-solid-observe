# B1 暖身清理（删 demo + 引擎冒烟测试）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 `observe-bootstrap` 的 demo 包（`DemoMain`/`DemoPipelineFactory`），并用一个无 Spring 的引擎冒烟单元测试承接其"手动装配跑通 pipeline"的验证价值。

**Architecture:** demo 是生产代码里的独立 main 入口（技术债）。删 demo 包后，其验证价值（绕开 Spring、手动装配 `GroovyScriptEngineImpl` + `LinearPipelineExecutor` + `DefaultPipelineRunner` + Noop 协作者、跑一个最小 Groovy pipeline）由一个新的 JUnit 测试承接。符合 `CONTEXT.md` Conventions："不在 production 代码里留 demo / 手动 main 入口；引擎冒烟验证由测试承接"。

**Tech Stack:** Java 17、JUnit 5、AssertJ、Groovy（脚本节点）、现有 kernel/pipeline/alerting 组件。

## Global Constraints

- 项目根有 `CONTEXT.md` 与 `docs/adr/`，设计权威以 ADR 为准；本计划对应 `docs/superpowers/specs/2026-07-18-observe-refactor-batches-design.md` §5.1 B1。
- 不使用 FK、引用完整性靠应用层（项目铁律，本批不涉及）。
- 每批结束（本批 = B1 全部任务完成）必须 `mvn compile` + `mvn test` 全绿。
- B1 不改 Event/Subscription/Alert 任何领域模型（那些是 B2–B5 的事）；本批仅删 demo + 加测试，沿用现状 API（`Event.EventMeta`/`Op`/`SourceType`/`DefaultAlertsApi(ctx)` 单参构造器等）。

## File Structure

- **Delete**: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo/DemoMain.java`
- **Delete**: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo/DemoPipelineFactory.java`
- **Create**: `observe-bootstrap/src/test/java/com/imsw/observe/bootstrap/EngineSmokeTest.java` —— 无 Spring 手动装配引擎、跑最小 pipeline、断言告警 emit 与短路行为。

`EngineSmokeTest` 单一职责：验证"Groovy 脚本节点 → ctx 传递 → alerts.emit → AlertSink 收集 → short-circuit"引擎闭环在不启动 Spring 的情况下成立。测试内嵌最小 Noop 协作者（事务/记录器/DbApi）和一个收集型 AlertSink（区别于 demo 的丢弃型——测试需要断言告警被 emit）。

---

## Task 1: 新增无 Spring 引擎冒烟测试（先加测试，让 demo 的验证价值先被承接）

**Files:**
- Create: `observe-bootstrap/src/test/java/com/imsw/observe/bootstrap/EngineSmokeTest.java`

**Interfaces:**
- Consumes（现状 API，本批不改）:
  - `com.imsw.observe.kernel.event.model.Event` / `Event.EventMeta` / `Op` / `SourceType`
  - `com.imsw.observe.pipeline.domain.Pipeline` / `NodeSpec` / `ErrorPolicy`
  - `com.imsw.observe.pipeline.application.PipelineRunner` / `PipelineExecutor`
  - `com.imsw.observe.pipeline.infrastructure.engine.DefaultPipelineRunner` / `LinearPipelineExecutor` / `ScriptNode`
  - `com.imsw.observe.pipeline.infrastructure.script.GroovyScriptEngineImpl`
  - `com.imsw.observe.alerting.infrastructure.alert.DefaultAlertsApi`（单参构造器 `new DefaultAlertsApi(ctx)`）
  - `com.imsw.observe.kernel.alert.spi.AlertSink` / `AlertsApi`
  - `com.imsw.observe.kernel.script.spi.DbApi` / `GroovyScriptEngine`
  - `com.imsw.observe.kernel.transaction.spi.TransactionOperator` / `TransactionOperator.TransactionCallback`
  - `com.imsw.observe.kernel.execution.spi.ExecutionRecorder`
  - `com.imsw.observe.kernel.event.model.ExecutionContext` / `AlertSignal`
- Produces: 无（本任务是 B1 的全部新增代码，后续 B2+ 批次不依赖本测试的具体内部结构）。

- [ ] **Step 1: 写测试文件 `EngineSmokeTest.java`**

完整内容（两个 case：amount 未超限不 emit + 不短路；amount 超限 emit CRITICAL + 短路）：

```java
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
        assertThat(signal.severity()).isEqualTo(AlertSignal.Severity.CRITICAL);
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
                executor, sink, new NoopTransactionOperator(), new NoopExecutionRecorder());
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
                "smoke-pipeline",
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
        Event.EventMeta meta =
                new Event.EventMeta(SourceType.CDC, "smoke-mq", "test_db", "orders", Map.of());
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
```

- [ ] **Step 2: 运行新测试，验证通过**

Run: `mvn -q -pl observe-bootstrap test -Dtest=EngineSmokeTest`
Expected: PASS（两个测试方法都绿）。

> 说明：这一步是"先确保新测试在 demo 仍存在时就通过"，证明新测试已独立承接验证价值，再在 Task 2 安全删除 demo。

- [ ] **Step 3: 提交（先加测试）**

```bash
git add observe-bootstrap/src/test/java/com/imsw/observe/bootstrap/EngineSmokeTest.java
git commit -m "$(cat <<'EOF'
test(bootstrap): add no-Spring engine smoke test to backstop demo removal

手动装配 Groovy 引擎 + 线性执行器 + Runner + Noop 协作者，验证脚本节点→ctx→alerts.emit→短路闭环。承接即将删除的 demo 的验证价值（CONTEXT.md Conventions）。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 删除 demo 包

**Files:**
- Delete: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo/DemoMain.java`
- Delete: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo/DemoPipelineFactory.java`
- Delete (空目录): `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo/`

**Interfaces:**
- Consumes: Task 1 的 `EngineSmokeTest` 已承接 demo 的验证价值（Task 1 Step 2 已验证通过）。
- Produces: 无（demo 包消失，无外部引用——已确认 `grep -rl bootstrap.demo` 仅 demo 包内部互引用）。

- [ ] **Step 1: 确认无外部引用**

Run: `grep -rl "bootstrap.demo" --include="*.java" . | grep -v /target/`
Expected: 仅输出 `DemoMain.java` 和 `DemoPipelineFactory.java` 自身（demo 包内部互引用）。若出现其他文件，停下排查——说明有外部代码依赖 demo，需先处理。

- [ ] **Step 2: 删除 demo 包两个文件 + 空目录**

```bash
git rm observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo/DemoMain.java
git rm observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo/DemoPipelineFactory.java
rmdir observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo 2>/dev/null || true
```

- [ ] **Step 3: 全量编译 + 全量测试，验证 B1 收尾绿**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS，全部测试通过（含 Task 1 的 `EngineSmokeTest`，不含已删除的 demo）。

- [ ] **Step 4: 确认 demo 残留为零**

Run: `grep -r "bootstrap.demo" --include="*.java" . | grep -v /target/ || echo "no residual"`
Expected: 输出 `no residual`。

- [ ] **Step 5: 提交（删 demo）**

```bash
git add -A observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/demo
git commit -m "$(cat <<'EOF'
chore(bootstrap): remove demo package (DemoMain/DemoPipelineFactory)

demo 是生产代码里的独立 main 入口（技术债），验证价值已由 EngineSmokeTest 承接。符合 CONTEXT.md Conventions "不在 production 代码里留 demo / 手动 main 入口"。

BREAKING CHANGE: 删除 DemoMain 入口（无外部引用）。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review（计划作者已执行）

1. **Spec 覆盖**：spec §5.1 B1 要求 = 删 demo + 检查 EndToEndFlowTest 是否覆盖、未覆盖则补轻量测试。
   - 删 demo → Task 2 ✓
   - "EndToEndFlowTest 是否覆盖" → 已查证：`EndToEndFlowTest` 是 `@SpringBootTest`（full Spring + H2），**不**覆盖"无 Spring 手动装配"。→ 需补测试 ✓
   - 补轻量测试 → Task 1 `EngineSmokeTest` ✓
2. **占位扫描**：无 TBD/TODO；所有步骤含完整代码与精确命令。✓
3. **类型一致性**：`EngineSmokeTest` 用的现状 API（`Event.EventMeta(SourceType,String,String,String,Map)`、`new DefaultAlertsApi(ctx)` 单参、`AlertSignal.Severity.CRITICAL`、`ErrorType` 全限定名）均与代码现状一致。`CapturingAlertSink` 用 `ctx.data().drainNewAlerts()`（与 `DefaultAlertSink` 同款用法）✓。
4. **顺序合理性**：Task 1 先加测试并验证通过 → Task 2 再删 demo，保证任何时刻"无 Spring 引擎验证"都不缺失。✓
