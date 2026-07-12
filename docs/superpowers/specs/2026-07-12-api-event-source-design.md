# API 事件入口设计（ApiSource HTTP 触发）

- 日期：2026-07-12
- 状态：已确认，待编写实现计划
- 范围：为 `ApiSource` 接入一个 HTTP 入口，使外部调用方能通过 REST API 触发流水线。多 MQ 接入（IBM MQ / RabbitMQ）属于独立工作，不在本 spec 范围内。

## 1. 背景与动机

`ApiSource`（`observe-pipeline/.../infrastructure/source/ApiSource.java`）当前在 `WorkerConfig` 中作为 Spring bean 被装配并启动，但全仓库没有任何地方调用它的 `submit(Event)` 入口。也就是说 API 这条来源链路"已注册、已启动，但无人投递事件"，端到端不通。

本 spec 补齐这一环：新增一个 HTTP 端点，外部调用方提交事件 → 转成 `Event` → `apiSource.submit(event)` → 进入既有 `SourceDispatcher` 异步流水线。

## 2. 设计目标与非目标

**目标**
- 提供 `POST /api/v1/events` 端点，接收 JSON 事件并投递到 `ApiSource`。
- 纯异步语义：立即返回 `202 Accepted`，不等待流水线执行。
- 不改动 `observe-kernel` 的事件模型（`Event` / `EventMeta` record）。
- 沿用现有 controller 与异常处理约定。

**非目标**
- 同步等待执行结果、长轮询、WebSocket 推送（v1 不做）。
- 鉴权 / 限流（由后续独立工作处理）。
- 多 MQ 来源接入（独立 spec）。

## 3. 架构与端点

- **方法/路径**：`POST /api/v1/events`
  - 路径前缀 `/api/v1` 与现有 controller（`/api/v1/pipelines` 等）保持一致。
- **归属**：`observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/EventController.java`
  - 沿用 controlplane 现有分层：HTTP 入口统一放在 `interfaces` 包下。
- **依赖注入**：构造注入 `ApiSource`。`ApiSource` 是 `WorkerConfig`（bootstrap）装配的 bean，controlplane 模块被 bootstrap 依赖，Spring 容器内可直接连上，无需额外 `@ComponentScan`。
- **返回**：`202 Accepted`，body 为 `{"eventId": "<uuid>"}`。

为什么纯异步：`submit()` 之后事件异步经过 `SourceDispatcher → SubscriptionMatcher → runnerPool → runner.run(...)`，调完立即返回时流水线尚未跑完。`202` 与现有 runnerPool 异步模型一致，不阻塞 HTTP 线程。执行结果/失败由既有 `ExecutionRecorder` / `FailedExecution` 记录，调用方可通过（未来的）执行查询接口获取。

## 4. 请求体 → `Event` 映射

`Event` record 结构：
```java
record Event(EventMeta meta, Map<String,Object> before, Map<String,Object> after, Op op, Instant sourceTs)
record EventMeta(SourceType sourceType, String source, String db, String table, Map<String,Object> attributes)
```

请求 DTO（`EventController` 内部 record，参考现有 controller 风格）：

```json
POST /api/v1/events
{
  "source": "order-service",
  "table": "orders",
  "op": "CREATE",
  "before": { ... },
  "after":  { ... },
  "attributes": { ... }
}
```

字段规则：
- `source`（必填）：事件来源标识，缺失 → `IllegalArgumentException` → `400`。
- `op`（可选，默认 `CREATE`）：取值为 `Op` 枚举名。
- `table`（可选）。
- `before` / `after`（可选）：透传到 `Event.before` / `Event.after`。
- `attributes`（可选）：透传到 `EventMeta.attributes`，并用于承载服务端生成的 `eventId`（见 §5）。

服务端固定填充：
- `meta.sourceType = SourceType.API`（因为是 API 入口）。
- `sourceTs = Instant.now()`（调用方不传时间，服务端盖戳）。
- `db`：API 来源无"数据库"语义，留空（`null`）。

## 5. 事件标识与返回

为支持事后查询，submit 时为事件生成 ID。**不改动 `Event` / `EventMeta` 结构**，而是把 ID 放进 `meta.attributes`：

- 生成 `UUID.randomUUID().toString()`，key 为 `eventId`，写入 `attributes`。
- 若调用方传入的 `attributes` 已含 `eventId`，服务端覆盖之（服务端为唯一事实来源）。
- HTTP 返回 `{"eventId": "<uuid>"}` + `202`。

> 备注：用 `attributes` 承载 ID 是 v1 的轻量方案。若后续执行查询/去重需要 `Event` 一等公民级的 ID，再单独评估是否给 `Event` 加字段（影响 kernel 模型，本 spec 不做）。

## 6. 错误处理

复用现有 `GlobalExceptionHandler`（`observe-controlplane/.../config/GlobalExceptionHandler.java`）：

- 请求体非法 / 缺 `source` → controller 抛 `IllegalArgumentException` → `400 Bad Request`，body `{"error": "..."}`。
- `apiSource.submit()` 抛 `IllegalStateException`（ApiSource 未 start，正常运行不应发生）→ 落入 `RuntimeException` handler → `500`。
  - 不为这一情况发明 `503`；与现有 handler 行为保持一致。
- 流水线执行失败**不**经此接口返回（异步）；由 `ExecutionRecorder` / `FailedExecution` 记录。

## 7. 测试

单元/切片测试（`observe-controlplane` 测试目录，参考现有 controller 测试风格）：

- **正常提交**：合法请求 → 验证 `ApiSource.submit(Event)` 被调用一次，且传入的 `Event`：
  - `meta.sourceType == SourceType.API`
  - `meta.source == req.source`
  - `sourceTs != null`
  - `meta.attributes` 含 `eventId`（非空）
  - 响应 `202`，body 含同样的 `eventId`。
- 使用 mock `ApiSource`（如 Mockito）捕获提交的 `Event` 做断言。
- **缺 `source`**：请求体不含 `source` → `400`。
- **默认 op**：不传 `op` → `Event.op == Op.CREATE`。

## 8. 影响面 / 改动清单

新增：
- `observe-controlplane/.../interfaces/EventController.java`（含请求 record、`eventId` 生成、`Event` 组装、`submit` 调用、`202` 返回）。
- 对应测试。

不改动：
- `ApiSource.java`（`submit(Event)` 已就绪，直接使用）。
- `Event` / `EventMeta`（kernel 模型）。
- `WorkerConfig`（`apiSource` bean 已存在）。
- `GlobalExceptionHandler`（行为已覆盖需求）。

## 9. 风险与开放问题

- **`ApiSource` 的可见性**：`apiSource` bean 定义在 bootstrap 的 `WorkerConfig`，controlplane 通过构造注入依赖它。需在实现时确认 controlplane 测试切片中能正确装配/注入该 bean（必要时在测试里手动 new 或提供测试配置）。
- **`op` 默认值 `CREATE` 的合理性**：API 来源是否普遍表达"创建"语义，留待实现时再确认；如不合理可改为必填。
