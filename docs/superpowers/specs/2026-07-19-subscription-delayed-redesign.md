# 订阅延时动作模型（Action = Run | Schedule | Cancel）

**Status**: ready for planning
**Date**: 2026-07-19
**Author**: grill-with-docs session
**相关**:
- 扇出改造（已完成）：`2026-07-19-subscription-multi-pipeline-fanout.md`
- 密封事件接口：ADR-0006
- 命名空间软隔离：ADR-0002

---

## 1. 背景

项目当前存在两个延时动作入口，且都不可用/不可维护：

- **入口 A（脚本运行时 `delayed.schedule(...)` binding）**：binding 从未注入脚本上下文，是死代码。
- **入口 B（subscription `actionType=SCHEDULE/CANCEL`）**：配置 + `DelayedActionHandler` 接线完整，但当前没有任何 subscription 实际配置过（开发阶段，无线上数据）。

扇出改造已经落地（subscription → pipelineIds 列表），但**有意保留了 `Action`/`DelayedActionHandler` 的现状**，留给本 spec 处理。

项目处于开发设计阶段、无线上数据、无向后兼容包袱，适合一次性把延时模型设计干净。

## 2. 目标

- 废弃两个旧入口，统一成一种**订阅级 actionType**模型。
- 支持 schedule-only（延迟 SQL 检查告警）和 schedule+cancel（订单超时复查）两类场景。
- 不为"同一订阅内混 Run + Schedule"的罕见场景引入复杂度（YAGNI，真出现时拆两条订阅）。

## 3. 非目标

- 跨重启持久化（当前 InMemory 实现重启丢失，生产化时再处理）。
- 跨 namespace 的 cancel（违反软隔离，明确不做）。
- "同一事件、不同 pipeline、不同 delay"的混合配置（罕见，拆两条订阅）。
- 扇出改造的回炉（已落地，本 spec 不动 `pipelineIds`）。

## 4. 核心设计

### 4.1 Action 抽象（订阅级，沿用现有 sealed interface）

```
sealed interface Action permits Run, Schedule, Cancel

record Run() implements Action {}
record Schedule(Duration delay, String correlationKeyPath) implements Action {}
record Cancel(String correlationKeyPath) implements Action {}
```

- 三种 actionType **平权**：每个订阅选其一，配置在订阅上。
- `Schedule` 必带 `delay` 和 `correlationKeyPath`。
- `Cancel` 只带 `correlationKeyPath`（无 delay，cancel 不创建定时器）。
- 配置层不强校验 schedule/cancel 是否配对——这是**业务约定**，平台不维护配对关系。

### 4.2 三种 actionType 的运行期语义

| actionType | dispatcher 匹配后做什么 | fire 时做什么 |
|---|---|---|
| `RUN` | 立即扇出 N 个 pipeline（现有逻辑） | — |
| `SCHEDULE` | 抽 correlationKey → 算 delay → `DelayedEventStore.schedule(fullKey, fireTask, delay)` | fireTask 包装一个 `DelayedEvent(originalEvent, subscriptionId)` 投回 dispatcher → 走 RUN 扇出路径 |
| `CANCEL` | 抽 correlationKey → `DelayedEventStore.cancel(fullKey)` | — |

**核心洞察**：SCHEDULE 的 fire 实质是"伪造一次 RUN"——`DelayedEvent` 绕过 matcher 复用 Run 的扇出逻辑（与 ADR-0006 §9.2 一致）。

### 4.3 correlationKey 命名空间：namespace 级

```
fullKey = subscription.namespace() + ":" + rawKey
```

- **namespace 级**意味着同 namespace 内的 schedule/cancel 自然配对，cancel 可从任意订阅（CDC/CRON/API）发起。
- 跨 namespace 不能 cancel（软隔离铁律，ADR-0002）。
- prefix 由 handler 层拼装，`DelayedEventStore` 端口签名不变（仍是 `schedule(key, ...)` / `cancel(key)`）。

### 4.4 Schedule/Cancel 不强制配对

| 场景 | 配置形态 |
|---|---|
| **schedule-only**（延迟 SQL 检查告警） | 一条订阅 action=SCHEDULE，绑 N 个 pipeline。事件来了 schedule，到期 fire 扇出。 |
| **schedule + cancel**（订单超时复查） | 两条订阅：A 订阅 INSERT + action=SCHEDULE，B 订阅 UPDATE + action=CANCEL。两边 keyPath 写同一个业务字段（如 `after.orderId`）。运行期通过 key 值自然对齐。 |
| **cancel-only**（运维清理、人工撤销） | 一条订阅 action=CANCEL，可来自 CRON/API/CDC。 |

平台在文档示例里给出"配对约定"（建议两边 keyPath 同字段），但**不做配置期强校验**。

### 4.5 DelayedEventStore 端口（沿用）

```
interface DelayedEventStore {
    void schedule(String key, Runnable fireTask, Duration delay);
    void cancel(String key);
    int  pendingCount();
    void shutdown();
}
```

`InMemoryDelayedEventStore` 行为：
- **schedule 替换语义**：同 key 已存在 → 取消旧任务（`cancel(false)`）→ 注册新任务。
- **cancel 幂等**：key 不存在或已 fire → no-op + WARN 日志。
- **in-flight fire 落空**：fire 已被 SES 线程取出执行时 cancel 到了，`ScheduledFuture.cancel(false)` 不能停掉正在跑的任务——**接受这个语义**，cancel 只撤销"还没到时间的"。
- **重启丢失**：InMemory 不持久化，进程重启所有延迟任务消失（生产化时再处理）。

### 4.6 DelayedActionHandler 重构（对齐订阅级 action）

当前 handler 在 `submitMatched` 内按 `(sub, pipeline)` 调用——**与路 A 的"订阅级 action"语义不符**。修正方向：

- dispatcher 在 matcher 命中订阅后，**先看 `sub.action()` 类型**再分发：
  - `Run` → 走现有扇出路径（每个 pipeline 独立 inFlight 许可 + 故障隔离）。
  - `Schedule` → 调 `DelayedEventStore.schedule(nsKey, () -> redispatchAsDelayedEvent(sub, originalEvent), delay)`。**不在 pipeline 级调用**，schedule 是订阅级资源。
  - `Cancel` → 调 `DelayedEventStore.cancel(nsKey)`。同样不在 pipeline 级。
- `DelayedActionHandler` 可能简化或并入 dispatcher——具体形态在 plan 阶段定。

### 4.7 Schema（沿用，不动）

`subscriptions` 表已有列足够：
- `action_type VARCHAR`（RUN/SCHEDULE/CANCEL）
- `schedule_delay_ms BIGINT`（仅 SCHEDULE 时非 null）
- `schedule_correlation_key_path VARCHAR`（SCHEDULE/CANCEL 时非 null）
- `ck_sub_action_type`（约束：非 SCHEDULE 时 delay 必为 null；非 SCHEDULE/CANCEL 时 keyPath 必为 null）

**不引入** `type` 列（FANOUT/DELAYED）、**不引入** JSON 列、**不引入** 新表。

## 5. 决策日志

| # | 决策 | 理由 |
|---|---|---|
| D1 | 三种 actionType 平权（Run/Schedule/Cancel），订阅选其一 | 简化抽象，避免"DELAYED 订阅类型"的组合爆炸 |
| D2 | Action 属于订阅（路 A），非 pipeline binding | YAGNI——混 Run+Schedule 场景罕见，拆两条订阅可解；订阅级语义与扇出对称（SCHEDULE 到期 fire = RUN 扇出） |
| D3 | schedule 和 cancel 不强制配对 | schedule-only 是主流场景（延迟 SQL 检查告警）；配对是业务约定，平台不维护 |
| D4 | correlationKey 命名空间 = namespace 级 | 支持 cancel 从任意订阅（含 CRON/API）发起；跨 namespace 不能 cancel（软隔离） |
| D5 | DelayedEventStore cancel 幂等 + in-flight 落空 | 简单优先；fire 已开始就让它跑完是合理业务语义 |
| D6 | 沿用现有 schema 扁平列 | 已足够，无需引入 type/JSON/新表 |
| D7 | DelayedActionHandler 重构为订阅级分发 | 扇出后遗留的 `(sub, pipeline)` 调用上下文与路 A 不符，必须清理 |

## 6. 影响范围

### 6.1 代码改动

- **`observe-pipeline/.../domain/subscription/Action.java`**：移除已删的 `Schedule.pipelineId` 死字段（扇出期已删，本 spec 确认）；`Cancel` 字段沿用。
- **`observe-pipeline/.../application/DelayedActionHandler.java`**：重构为订阅级分发，或并入 `SourceDispatcher`。
- **`observe-pipeline/.../application/SourceDispatcher.java`**：matcher 命中后看 `sub.action()` 分发；不在 `submitMatched` 内调用延迟 handler。
- **`observe-config/.../SubscriptionDefinition.java`**：`ActionType` 枚举沿用；校验逻辑确认（SCHEDULE 必带 delay+keyPath、CANCEL 必带 keyPath、非 SCHEDULE/CANCEL 时二者必为 null）。
- **`observe-pipeline/.../infrastructure/delayed/InMemoryDelayedEventStore.java`**：行为不变，已对齐 D4/D5。
- **`observe-bootstrap/.../worker/config/WorkerConfig.java`**：handler 接线可能简化。

### 6.2 不动的部分

- `pipelineIds`（扇出，已落地）。
- `DelayedEventStore` 端口、`DelayedEvent` 模型、`EventPaths` key 提取。
- `subscriptions` schema（V1__init.sql）。
- `ActionType` 枚举值（RUN/SCHEDULE/CANCEL）。
- ADR-0001～0007 的所有契约。

### 6.3 测试

- 新增 `SubscriptionCrudServiceTest`：CANCEL 校验（必带 keyPath、非 CANCEL 时 keyPath 必为 null）。
- 新增 `SourceDispatcherTest`：SCHEDULE 命中后 dispatcher 调 store.schedule（不在 pipeline 级）、CANCEL 命中后调 store.cancel。
- 新增 `EndToEndFlowTest`：schedule-only 场景（CDC INSERT → schedule delay → fire → 告警落库）。
- 新增 namespace 级 key 隔离测试：同 namespace 内 cancel 命中、跨 namespace cancel 不命中。
- 现有 `InMemoryDelayedEventStoreTest` 沿用，补 in-flight fire 落空用例。

## 7. 开放问题（plan 阶段再决）

- `DelayedActionHandler` 是保留为独立类还是并入 `SourceDispatcher`？倾向**并入**（dispatcher 已经持有 store 引用，再隔一层没必要）。
- `SubscriptionMatcher.Matched` 是否需要扩展携带 action 元数据？倾向**不需要**——dispatcher 拿到订阅后直接读 `sub.action()` 即可。
- namespace 级 key 拼装的字符串分隔符（`:`）是否需要防业务字段里出现 `:`？倾向**不做**——业务字段里出现 `:` 是病态，文档警告即可。

## 8. 不在本 spec 范围

- 扇出（已完成）。
- 跨重启持久化（生产化时另起 spec）。
- 跨 namespace cancel（明确不做）。
- DELAYED 订阅类型 / delayedRule 独立实体 / pipeline binding action（已否决）。
