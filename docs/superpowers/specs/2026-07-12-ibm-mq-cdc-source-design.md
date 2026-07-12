# IBM MQ CDC 来源接入设计（含可插拔 MessageParser 地基）

- 日期：2026-07-12
- 状态：已确认，待编写实现计划
- 范围：接入 IBM MQ 作为 CDC 来源（worker 作为 JMS consumer 主动消费队列，消息体为 XML），并落地一个可插拔的 `MessageParser` 抽象，作为未来 RabbitMQ / 其他来源各自实现解析的扩展点。
- 不在范围：RabbitMQ 接入（独立后续工作）、多 MQ 同时接入（v1 单进程单 MQ）、消息格式转换服务、死信队列基础设施。

## 1. 背景与动机

现状：
- `CdcMessageSource`（pipeline 层端口）：`subscribe(Consumer<List<Event>>) + stop()`。
- `CdcMqSource`（pipeline 层）：持有一个 `CdcMessageSource`，订阅后转发给 dispatcher。是几乎无附加逻辑的"胶水"层。
- `InMemoryCdcMessageSource`（bootstrap 层）：唯一的端口实现，靠人调 `push()` 喂数据，仅用于 demo/测试。
- `WorkerConfig.cdcMqSource` bean：new `CdcMqSource(cdcMessageSource)`，写死指向内存实现。

结论：当前结构没有接任何真实 MQ。

本 spec 的两件事：
1. 接 IBM MQ（JMS consumer + XML 消息），使 CDC 来源端到端真实可用；
2. 顺带修正 §3 中评估出的设计问题——合并掉 `CdcMqSource` 这层空壳胶水。

## 2. 设计目标与非目标

**目标**
- worker 作为 JMS consumer 主动消费 IBM MQ 队列，消息体 XML，解析为 `Event`，提交进既有 `SourceDispatcher` 异步链路。
- 提供 at-least-once 投递（ack 边界 = 进入 dispatcher）。
- 抽出可插拔 `MessageParser` 抽象，IBM MQ XML 解析器是第一个实现；未来来源各写各的 parser。
- 保持 `observe-pipeline` 对 MQ 客户端零依赖。

**非目标**
- RabbitMQ 接入、多 MQ 同时接入、配置化来源开关的完整体系（v1 仅给 IBM MQ 一个开关）。
- 死信/毒队列、自动重连策略、消息去重（v1 已知限制，见 §9）。
- XML → Event 的"通用配置驱动映射"——v1 用一个写死的示例解析器，真实映射规则由用户自行实现（见 §6）。

## 3. 对现有设计的评估与调整（合并 CdcMqSource）

评估结论：分层意图（端口在 pipeline、实现在 bootstrap；pipeline 不依赖 MQ 客户端）正确，保留。但 `CdcMqSource` 这一层是"空壳胶水"——它只持有 `CdcMessageSource` 并转发，不增加任何能力（无限流、无指标、无背压），等于两个抽象抢同一职责。

**调整决定：合并。去掉 `CdcMqSource`，让每个具体来源直接 `implements Source`。**

- `IbmMqCdcSource`（新）直接 `implements Source`：`start(listener)` 内部启动 JMS 消费、并把 listener 注册为 sink；`stop()` 关闭 JMS 连接。
- `InMemoryCdcMessageSource` 同步改造为 `implements Source`（rename 为 `InMemoryCdcSource` 或保留原名仅改接口），其 `push(List<Event>)` 转发到 listener。
- 删除 `CdcMqSource.java` 及 `WorkerConfig` 中对它的引用。
- `CdcMessageSource` 接口：**删除**。它原本是 `CdcMqSource` 与实现之间的中间端口，合并后不再有存在意义。来源直接实现 `Source`。

代价：每个来源实现需自行写 `start`/`stop`（约 4-10 行样板）。换来：少一层、少一次转发、链路直白、无空壳类。

> 备选方案（未采纳）：保留两层、把 `CdcMqSource` 重命名为通用 `MessageSourceAdapter`。不采纳理由——YAGNI，当前每个来源都能直接 `implements Source`，adapter 没有不可替代的职责。

## 4. MessageParser 抽象

新增接口，放 `observe-pipeline/.../infrastructure/source/`：

```java
public interface MessageParser<M> {
    Event parse(M raw) throws MessageParseException;
}
```

- 泛型 `M`：原始消息类型。IBM MQ 走 JMS 用 `javax.jms.TextMessage`；未来 RabbitMQ 用其原生消息类型。
- 解析失败抛 `MessageParseException`（kernel 层 `com.imsw.observe.kernel.error` 下新增，继承 `ObserveException`）。
- **为什么用泛型而非 `byte[]`/`String`**：不同 MQ 原生消息带不同元数据（MQMD header、JMS properties、RabbitMQ headers），部分字段可能需进入 `EventMeta.attributes`；固定成 `byte[]` 会丢失这些。

> 具体的 `MessageParser` 实现（如 IBM MQ 的 XML 解析器）放 bootstrap 层，因其依赖的 MQ 客户端与 XML 处理都在 bootstrap。pipeline 模块只定义接口。

## 5. IbmMqCdcSource（JMS 消费 + at-least-once）

新增类，放 `observe-bootstrap/.../worker/source/`，`implements Source`：

```java
public final class IbmMqCdcSource implements Source {
    // 构造: JmsConnectionFactory, queue name, MessageParser<TextMessage>, batchSize, batchTimeoutMillis
    // start(listener): 保存 listener; 打开 JMS Connection/Session/Consumer (CLIENT_ACKNOWLEDGE);
    //                  注册 MessageListener -> onMessage
    // onMessage(msg): parse -> 入 buffer; buffer 满/超时 -> flush
    // flush(batch, originalMsgs): listener.onBatch(batch); 全部成功 -> 逐条 msg.acknowledge()
    //                              异常 -> 不 ack（MQ 重投）
    // stop(): 关闭 Connection
}
```

关键设计：
- **`type()` 返回 `SourceType.CDC`**：IBM MQ 来源在 v1 即 CDC 场景。来源实现决定自己的 `type()`（不硬编码在公共层）。
- **懒启动**：`start(listener)` 被调用时才打开 JMS 连接并开始消费。与既有"先 start 再有数据"的生命周期一致。
- **攒批**：`Source.start` 拿到的 `EventListener.onBatch` 接收 `List<Event>`，source 内部按条数（默认 50）或时间窗（默认 200ms）攒批 flush。
- **依赖**：`com.ibm.mq.allclient`（含 JMS）加入 bootstrap pom。

### 5.1 at-least-once 语义与 ack 边界（关键约定）

- **JMS 会话用 `CLIENT_ACKNOWLEDGE`**（不用 `AUTO_ACKNOWLEDGE`）。
- **ack 边界 = `listener.onBatch(batch)` 成功返回**。一批消息：解析 + 攒批 → 调 `onBatch` → 成功后对该批每条消息 `acknowledge()`；若 `onBatch` 抛异常，整批不 ack，MQ 重投。
- **解析失败（`MessageParseException`）→ 不 ack 该消息 → MQ 重投**，并记日志（含原始报文）。**不跳过、不丢消息**。坏消息无限重投的风险 v1 不解决（见 §9）。
- **ack 边界之后是异步执行**：`listener.onBatch` 即 `SourceDispatcher.onBatch`，内部把事件丢进 `runnerPool` 异步执行后立即返回。因此"进入 dispatcher"即视为本批 ack 通过；**pipeline 实际执行失败不会回 MQ**，由既有 `ExecutionRecorder` / `FailedExecution` 记录。这是业界常见做法（避免跨线程同步 ack 的复杂度）。

> 明确范围：at-least-once 覆盖 "MQ 取出 → 解析 → 进入 dispatcher" 这段；不覆盖 "pipeline 执行成功"。

## 6. IbmMqXmlParser（示例实现，真实规则用户自实现）

新增类，放 `observe-bootstrap/.../worker/source/`，`implements MessageParser<TextMessage>`：

- 用 JDK 自带 `javax.xml.parsers.DocumentBuilder`（不引新 XML 依赖）。
- **以下映射规则仅为占位示例，真实规则由用户自行实现**。示例假设一种简单 XML：

假设 XML 报文形如：
```xml
<event>
  <source>order-service</source>
  <table>orders</table>
  <op>CREATE</op>
  <after>
    <id>123</id>
    <status>PAID</status>
  </after>
</event>
```

示例映射（占位）：
- `event/source` 文本 → `EventMeta.source`
- `event/table` 文本 → `EventMeta.table`
- `event/op` 文本 → `Event.op`（解析为 `Op` 枚举，失败默认 `CREATE`）
- `event/after` 子树 → `Event.after`（节点名→文本值）
- `event/before` 子树（若存在）→ `Event.before`
- 固定填充：`EventMeta.sourceType = SourceType.CDC`；`sourceTs = Instant.now()`。
- 解析出错（XML 非法/缺关键字段）→ 抛 `MessageParseException`。

> 注：上述结构是虚构示例。用户需根据真实 IBM MQ XML 报文重写映射逻辑。spec 不为虚构格式做更强约束。

## 7. 配置

`application.yml` 在 `observe.worker` 下新增 `ibm-mq` 段：

```yaml
observe:
  worker:
    enabled: true
    # ...既有 runner/cron 配置...
    ibm-mq:
      enabled: false              # 默认关闭，本地/demo 不依赖真实 MQ
      host: ...
      port: 1414
      queue-manager: ...
      channel: DEV.APP.SVRCONN
      queue: APP.Q
      batch-size: 50
      batch-timeout-millis: 200
```

- 新增 `IbmMqProperties`（`@ConfigurationProperties("observe.worker.ibm-mq")`）绑定。
- `IbmMqCdcSource` bean 在 `WorkerConfig` 用 `@ConditionalOnProperty(prefix="observe.worker.ibm-mq", name="enabled", havingValue="true")` 装配。关闭时该 bean 不存在，`start` 链路不触发，不影响其余功能。

## 8. WorkerConfig 改造

- 删除：`cdcMqSource` bean、`cdcMqSource` 对 `InMemoryCdcMessageSource` 的引用。
- 新增：`ibmMqCdcSource` bean（条件装配，`start(dispatcher::onBatch)`）。
- `InMemoryCdcMessageSource` bean：保留，用于 demo/测试；改造为 `implements Source` 后，在 demo 入口（`DemoMain` / `DemoPipelineFactory`）改为通过 `start(listener)` + `push(...)` 驱动。
- 其余 source（`cronSource`、`apiSource`）不动。

## 9. 风险与开放问题

- **XML 报文格式未知** → §6 示例为占位，真实映射由用户实现。
- **坏消息无限重投**：at-least-once 下解析失败的消息会被 MQ 反复重投，无死信机制。v1 已知限制，未来加死信/毒队列 + 最大重试次数。
- **连接断开无自动重连**：v1 依赖 JMS client 自带能力或运维处理；不做应用层重连。
- **IBM MQ 客户端体积大**（`com.ibm.mq.allclient` 数十 MB）→ v1 接受；若成问题再独立模块隔离。
- **ack 边界 = 进入 dispatcher**：pipeline 异步执行失败不回 MQ（见 §5.1）。如未来要求"执行成功才 ack"，需引入跨线程同步 ack + 批次完成回调，复杂度显著上升，v1 不做。
- **`InMemoryCdcMessageSource` 改 `implements Source` 后对 demo/测试的影响**：需同步调整 `DemoMain`/`DemoPipelineFactory` 及相关测试的驱动方式，实现阶段验证。
