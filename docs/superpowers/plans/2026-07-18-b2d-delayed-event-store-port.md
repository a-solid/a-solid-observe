# B2d 延时层端口（DelayedEventStore）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 修复 `DelayedActionHandler`（application 层）直接依赖 `InMemoryDelayedEventStore`（infrastructure 层）的层间耦合（ADR-0001）：抽 `DelayedEventStore` 端口到 application 层（纯调度原语），infrastructure 实现它，handler 只依赖端口。

**Architecture:** 按 grilling 方案乙（纯调度原语端口）。端口 `DelayedEventStore`（application 层）只暴露 `schedule(correlationKey, task)` / `cancel(correlationKey)` / `pendingCount()` / `shutdown()` —— 纯调度，不含 domain 逻辑。correlationKey 提取（`EventPaths.get`）+ DELAYED 包装 event 构造留在 `DelayedActionHandler`（application 层 domain 逻辑）。`InMemoryDelayedEventStore implements DelayedEventStore`（infrastructure，SES + map）。handler 依赖端口接口。

**Tech Stack:** Java 17、Spring Boot、ScheduledExecutorService。

> **本子计划是 B2 的第 4 份（共 4 份，B2 收尾）**。前置 B2a/B2b/B2c（已合并）。本份修分层 + 把 domain 逻辑（correlationKey 提取、DELAYED 包装）从 store 上移到 handler。

## Global Constraints

- 设计权威：`CONTEXT.md` Conventions「依赖倒置：application 层不 import infrastructure 层具体类，通过 application 层端口接口交互（参考 DelayedEventStore）」+ `docs/adr/0001`。
- 方案乙：端口 = 纯调度原语（schedule/cancel/pendingCount/shutdown），correlationKey 提取 + DELAYED 包装留 handler。
- 不改延时事件的运行时行为（schedule/cancel/fire 语义不变，§9 不变）。
- 不改 Event 模型（B3）、不动 namespace（B2b 已做）。
- 每批结束 `mvn compile` + `mvn test` 全绿。

## File Structure

**Create:**
- `observe-pipeline/application/DelayedEventStore.java` —— 端口接口（纯调度原语）。

**Modify:**
- `observe-pipeline/application/DelayedActionHandler.java` —— 依赖 `DelayedEventStore` 端口（非 InMemoryDelayedEventStore）；承接 correlationKey 提取 + DELAYED 包装 + fire 触发（构造 task 传给 store）。
- `observe-pipeline/infrastructure/delayed/InMemoryDelayedEventStore.java` —— `implements DelayedEventStore`；schedule/cancel 改为接收 `correlationKey` + `Runnable/Task`（纯调度），移除 correlationKey 提取与 DELAYED 包装（上移到 handler）。
- `observe-bootstrap/worker/config/WorkerConfig.java` —— bean 装配：`DelayedEventStore` 端口 bean 类型；handler 注入端口。

**接口边界（端口签名）：**
```java
public interface DelayedEventStore {
    void schedule(String correlationKey, Runnable fireTask, Duration delay);
    void cancel(String correlationKey);
    int pendingCount();
    void shutdown();
}
```
- `schedule`：按 delay 延时执行 fireTask；同 key 老 task cancel(false)（Replace 语义）。
- `cancel`：移除 key 的 task（若有），cancel(false)；无则 no-op。
- `pendingCount`：当前待 fire 任务数。
- `shutdown`：关 SES + 清 map。

## Task 1: 抽 DelayedEventStore 端口 + 重构 handler/store 职责 + 装配 + 全量绿

**Files:**
- Create: `observe-pipeline/application/DelayedEventStore.java`
- Modify: `DelayedActionHandler.java`、`InMemoryDelayedEventStore.java`、`WorkerConfig.java`
- Test: `InMemoryDelayedEventStoreTest.java`、`SourceDispatcherTest.java`（适配）

**Interfaces:**
- Consumes: B2b 现状（Subscription 运行态含 namespace，Action.Schedule/Cancel）。
- Produces: `DelayedEventStore` 端口；handler 承接 domain 逻辑；store 纯调度。

- [ ] **Step 1: 写端口 `DelayedEventStore`**

```java
package com.imsw.observe.pipeline.application;

import java.time.Duration;

/**
 * 延时事件调度端口（application 层）。纯调度原语：按 correlationKey 调度/取消延时任务。
 *
 * <p>domain 逻辑（correlationKey 提取、DELAYED 包装 event 构造）由 {@link DelayedActionHandler}
 * 负责；本端口只管"到点执行 task"。实现见
 * {@link com.imsw.observe.pipeline.infrastructure.delayed.InMemoryDelayedEventStore}（SES + map）。
 * 未来换 Redis/DB 持久化实现时，handler 不变。
 */
public interface DelayedEventStore {

    /** 按 delay 延时执行 fireTask；同 correlationKey 的老 task cancel(false)（Replace 语义）。 */
    void schedule(String correlationKey, Runnable fireTask, Duration delay);

    /** 移除并 cancel(false) correlationKey 的 task；无则 no-op（幂等）。 */
    void cancel(String correlationKey);

    /** 当前待 fire 任务数。 */
    int pendingCount();

    /** 关闭调度器 + 清空待 fire 任务。 */
    void shutdown();
}
```

- [ ] **Step 2: 重构 `InMemoryDelayedEventStore` implements 端口（纯调度）**

把 schedule/cancel 改为接收 `correlationKey` + `Runnable fireTask`：
```java
public final class InMemoryDelayedEventStore implements com.imsw.observe.pipeline.application.DelayedEventStore {

    private final ScheduledExecutorService scheduler;
    private final ConcurrentMap<String, ScheduledFuture<?>> byCorrelationKey = new ConcurrentHashMap<>();

    public InMemoryDelayedEventStore(final ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;   // 不再需要 PipelineRunner —— fire 逻辑上移到 handler
    }

    @Override
    public void schedule(final String correlationKey, final Runnable fireTask, final Duration delay) {
        if (correlationKey == null) {
            LOG.warn("delayed schedule correlation key null");
            return;
        }
        ScheduledFuture<?> sf = scheduler.schedule(fireTask, delay.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> prev = byCorrelationKey.put(correlationKey, sf);
        if (prev != null) {
            prev.cancel(false);
        }
        LOG.info("delayed scheduled key={} delay_ms={}", correlationKey, delay.toMillis());
    }

    @Override
    public void cancel(final String correlationKey) {
        if (correlationKey == null) {
            LOG.warn("delayed cancel correlation key null");
            return;
        }
        ScheduledFuture<?> prev = byCorrelationKey.remove(correlationKey);
        if (prev != null) {
            prev.cancel(false);
            LOG.info("delayed cancelled key={}", correlationKey);
        }
    }

    @Override
    public int pendingCount() {
        return byCorrelationKey.size();
    }

    @Override
    public void shutdown() {
        scheduler.shutdown();
        byCorrelationKey.clear();
    }
}
```
移除原 `schedule(Subscription,Event,Pipeline,Duration,path)`、`cancel(Subscription,Event,path)`、`fire`、`wrapAsDelayed`、`extractKey`、`PipelineRunner runner` 字段（这些 domain 逻辑上移到 handler）。移除不再需要的 import（Event/Op/SourceType/Pipeline/Subscription/EventPaths/PipelineRunner/UUID/Map）。

- [ ] **Step 3: 重构 `DelayedActionHandler` 承接 domain 逻辑 + 依赖端口**

handler 负责：抽 correlationKey（EventPaths）、构造 DELAYED 包装 event、定义 fire task（调 runner.run）、调端口 schedule/cancel。

```java
package com.imsw.observe.pipeline.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Subscription;
import com.imsw.observe.pipeline.infrastructure.source.EventPaths;

public final class DelayedActionHandler {

    private final DelayedEventStore store;
    private final PipelineRunner runner;

    public DelayedActionHandler(final DelayedEventStore store, final PipelineRunner runner) {
        this.store = store;
        this.runner = runner;
    }

    public boolean handle(final Subscription subscription, final Event event, final Pipeline pipeline) {
        Action action = subscription.action();
        if (action instanceof Action.Schedule schedule) {
            String key = extractKey(event, schedule.correlationKeyPath());
            if (key == null) {
                return true; // 无法调度，视为已处理（避免 fall-through 到 RUN）
            }
            Instant scheduledAt = Instant.now();
            store.schedule(key, () -> fire(subscription, event, pipeline, key, scheduledAt), schedule.delay());
            return true;
        }
        if (action instanceof Action.Cancel cancel) {
            String key = extractKey(event, cancel.correlationKeyPath());
            store.cancel(key); // cancel 内部处理 null key（no-op + warn）
            return true;
        }
        return false;
    }

    private void fire(
            final Subscription subscription,
            final Event original,
            final Pipeline pipeline,
            final String correlationKey,
            final Instant scheduledAt) {
        try {
            Event delayed = wrapAsDelayed(original, subscription, correlationKey, scheduledAt);
            runner.run(pipeline, delayed, null);
        } catch (RuntimeException e) {
            // log（可加 logger）
        }
    }

    private static Event wrapAsDelayed(
            final Event original,
            final Subscription subscription,
            final String correlationKey,
            final Instant scheduledAt) {
        Event.EventMeta originalMeta = original.meta();
        Event.EventMeta meta = new Event.EventMeta(
                SourceType.CDC,
                "delayed:" + subscription.id(),
                originalMeta == null ? null : originalMeta.db(),
                originalMeta == null ? null : originalMeta.table(),
                Map.of(
                        "schedule_id", UUID.randomUUID().toString(),
                        "subscription_id", subscription.id(),
                        "original_event", original,
                        "scheduled_at", scheduledAt.toString(),
                        "fired_at", Instant.now().toString(),
                        "correlation_key", correlationKey));
        return new Event(meta, null, null, Op.DELAYED, Instant.now());
    }

    private static String extractKey(final Event event, final String path) {
        Object value = EventPaths.get(event, path);
        return value == null ? null : value.toString();
    }
}
```
（保留原 fire 的 try/catch + finally 语义：原 fire 在 finally 里 `byCorrelationKey.remove(correlationKey)`——现在 map 在 store 内部，handler 无法直接 remove。**决策**：fire task 完成后清理 map 的职责改由 store 的 ScheduledFuture 完成机制处理？不行——SES 的 ScheduledFuture 完成不会从 map 移除。**保留清理**：给端口加一个 `onFired(correlationKey)` 钩子，或在 store.schedule 包装 fireTask：`scheduler.schedule(() -> { try { fireTask.run(); } finally { byCorrelationKey.remove(key); } }, ...)`。**推荐后者**——store 在 schedule 时包装 fireTask 加 finally 清理，handler 不感知 map。修改 Step 2 的 schedule：`Runnable wrapped = () -> { try { fireTask.run(); } finally { byCorrelationKey.remove(correlationKey); } }; scheduler.schedule(wrapped, ...);`。这样 handler 的 fire 不需 finally 清理 map。）

> 实现时按上述"store 包装 fireTask 加 finally 清理"执行（Step 2 的 schedule 实现 accordingly）。handler 的 fire 保持纯业务（try/catch log）。

- [ ] **Step 4: 装配（WorkerConfig）**

`delayedEventStore` bean：`new InMemoryDelayedEventStore(ses)`（不再传 runner）。
`delayedActionHandler` bean：`new DelayedActionHandler(delayedEventStore, runner)`（加 runner）。
bean 类型用端口 `DelayedEventStore`（`@Bean public DelayedEventStore delayedEventStore(...)`），handler 注入端口。

- [ ] **Step 5: 适配测试**

`InMemoryDelayedEventStoreTest`：原测 schedule/cancel 的 Subscription+Event 形态——改为测端口形态（schedule(key, task, delay) / cancel(key)）。或重写为：schedule 一个 task、verify SES 到期执行、cancel 提前取消、pendingCount。
`SourceDispatcherTest`：若 mock 了 DelayedActionHandler 或 store，适配端口类型。

- [ ] **Step 6: 全量编译 + 测试**

Run: `mvn clean test`
Expected: BUILD SUCCESS，全绿（测试数与 B2c 一致 59，可能因 InMemoryDelayedEventStoreTest 重写而微调）。

- [ ] **Step 7: 提交**

```bash
git add observe-pipeline/ observe-bootstrap/
git commit -m "$(cat <<'EOF'
refactor(pipeline): extract DelayedEventStore port, fix layer coupling (ADR-0001)

DelayedActionHandler(application) 原直接依赖 InMemoryDelayedEventStore(infrastructure)。抽 DelayedEventStore 端口到 application 层（纯调度原语 schedule/cancel/pendingCount/shutdown，方案乙）；correlationKey 提取 + DELAYED 包装 event 上移到 handler（domain 逻辑归 application）；store implements 端口纯调度（SES+map，fire task 在 schedule 时包装 finally 清理 map）。handler 依赖端口。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

1. **Spec 覆盖**：B2 spec §5.2 B2.3 延时端口部分 + ADR-0001 依赖倒置 = Task 1。
2. **占位扫描**：端口签名 + handler/store 重构代码完整给出；Step 2 的 finally 清理包装明确。
3. **行为保真**：schedule/cancel/fire 语义不变（同 key Replace、cancel(false) 不中断在跑、fire 后清 map、DELAYED 包装字段一致）。fire 的 map 清理从"handler finally"改为"store schedule 包装 finally"——等价。
4. **风险**：`PipelineRunner` 从 store 移到 handler（装配加 runner 参数）。InMemoryDelayedEventStoreTest 需重写（测端口形态）。
