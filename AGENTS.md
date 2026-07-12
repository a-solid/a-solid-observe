# AGENTS.md

给 AI 协作者的工程规范。AI 在本项目内写代码 / 改代码 / 写提交前，必须先读完本文档。

本规范与下面两份文档互补：
- `docs/observe-platform-design.md` — 当前已实现 / 一期范围内置的设计
- `docs/observe-roadmap.md` — 远期演进、二期项、未实现功能、实施阶段

设计与规范冲突时，以本文档为准；本文档没覆盖的，以设计文档为准。

---

## 1. 项目背景

**a-solid-observe** 是一个**部门内多团队**共用的观测/监测平台：

- 监听 CDC MQ 数据作为事件、或被定时任务/HTTP API 触发
- 执行用户配置的检测逻辑（Groovy 脚本 + SQL UDF）
- 命中规则时落库告警 + 证据
- 告警通过 HTTP API 暴露给 Grafana / AlertManager 拉取

**技术栈**：Java 17、Spring Boot、Maven 多 module、Groovy、关系型 DB（不预设，schema 兼容 Sybase ASE）、OTel。

**场景边界**：规则作者为部门内可信用户。脚本沙箱采用 Groovy AST 白名单 + receiver 黑名单 + statement 黑名单 + timeout + 语句数上限，不追求 GraalJS 级强沙箱。

---

## 2. 架构原则（硬性约束）

1. **模块依赖方向严格单向**：`controlplane → config → pipeline → kernel`；`alerting → kernel`；`bootstrap` 装配所有。`kernel` 不依赖任何其他 module。违反依赖方向的代码立即拒绝。

2. **不使用数据库外键**（FK），引用完整性靠应用层保证。唯一约束除了 PK 不允许加。

3. **不预设 DB** → SQL 用标准语法、避免方言；JSON 字段用 `LONG VARCHAR` / `VARCHAR(n)`，应用层做序列化/反序列化；不用分区表、不用部分索引。

4. **节点类型单一** `script`：pipeline 内只有一种节点，执行 Groovy 脚本。不再为 mapping/sql/sp/rule 分别造节点类型。

5. **订阅字段过滤不走脚本引擎**：`Condition` DSL 是独立结构化 AST，用 `db/table/op` 索引 + 字段级谓词过滤；不要把 Groovy 引入订阅匹配。

6. **告警触发方式固定**：脚本通过 `alerts.emit(...)` 触发告警。不引入声明式 YAML 触发。

7. **Context 隐式全 pipeline 可见**：脚本通过 `ctx.set` / `ctx.get` 在节点间交换数据；`provides` / `reads` 是辅助元数据，不做强校验。

8. **事务边界 = 整个 pipeline**：从 pipeline 开始到结束共享一个事务（单 DataSource 约束）。不引入跨 DataSource 事务 / JTA。

9. **延时任务内存化**：一期 `InMemoryDelayedEventStore`，worker 重启丢失 PENDING 任务，业务靠 CDC 重发 + fingerprint 去重补偿。不在一期实现持久化。

10. **每加一个新的 module / 数据库表 / 脚本引擎**，先回到设计文档 + roadmap 评估是否在一期范围内；超范围的禁止私自加。

### 2.5 架构范式：Module-per-Bounded-Context + 四层分包（轻量 DDD）

本项目采用 **Module-per-Bounded-Context + 四层分包**（轻量 DDD），**不是经典战术 DDD**。

| 范式要素 | 本项目采用 | 明确排除（防 AI 过度补全） |
|---|---|---|
| Bounded context → Maven module | ✅ 6 module | — |
| 模块内分层（domain/application/infrastructure；controlplane 薄适配器仅 `interfaces/`） | ✅ | 业务模块不单设额外 `interfaces/` 子层（见 §3.2） |
| domain 纯净（零 framework / JPA / Jackson 依赖） | ✅ 硬约束（见 §8 红线） | — |
| 跨 context 通过 kernel 端口通信（`AlertSink` 等） | ✅ | — |
| Aggregate Root | ❌ domain 主要是 record / sealed interface | 不给 alerting/pipeline 补聚合根 |
| Domain Service（业务行为下沉 domain） | ❌ 行为极少，编排留 application | 不把 application service 逻辑下沉 domain |
| Domain Event Bus | ❌ | 不引入 |
| Repository 接口放 domain、infrastructure 实现 | ❌ Repository 直接在 infrastructure | 不切接口/实现两份 |

判定依据：本质是「配置驱动的规则执行引擎 + 告警存储」，大量代码是技术编排（事务、线程池、JPA、source adapter），不是业务规则；domain 大多是 record + sealed interface，几乎没行为，切战术 DDD 样板收益低、成本高，且与一期弱化测试（§9）的落地节奏冲突。

---

## 3. 模块与目录结构

### 3.1 6 个 Maven module

| Module | 职责 |
|---|---|
| `observe-kernel` | 共享内核：`Event`、`ExecutionContext`、`TypeConverter`、`GroovyScriptEngine`、异常体系；同时承载跨 bounded context 的契约端口——`AlertSignal`、`Severity`、`AlertSink` 接口（端口定义，实现留 alerting） |
| `observe-pipeline` | 核心 domain + application：`Pipeline` / `Node` / `NodeSpec` / `Source` / `EventListener` / `Subscription` / `Condition` / `Action`；`PipelineRunner` / `PipelineExecutor` / `SubscriptionMatcher` / `ScriptNode` / `DelayedActionHandler`；infrastructure：`LinearPipelineExecutor` / `CdcMqSource` / `CronSource` / `ApiSource` / `InMemoryDelayedEventStore`。pipeline 不直接落库告警——通过 kernel `AlertSink` 端口委托给 alerting |
| `observe-alerting` | 告警领域：domain record（`AlertEntity` / `EvidenceEntity` / `AlertStatus`）+ JPA Po（`AlertPo` / `EvidencePo`）+ Repository + mapper + JSON converter；infrastructure：`DefaultAlertSink`（实现 kernel `AlertSink` 端口，把 `AlertSignal` 落库成 `AlertEntity`）/ `EvidenceCollector` / `FingerprintCalculator` / `AnnotationRenderer` / `AlertResolveJob`；application：`AlertQueryService` / `AlertQueryApi` |
| `observe-config` | 规则配置：`PipelineDefinition` / `PipelineVersion`；CRUD service / version publish service / hot reloader / pipeline validator；JPA repository |
| `observe-controlplane` | 薄适配器层（无 domain/application）：REST controllers、DTOs、validators，直接调 config/alerting/pipeline 的 application service |
| `observe-bootstrap` | main 入口 + 装配：main / worker / controlplane / demo 四进程层包（见 §3.2.2） |

### 3.2 module 内 DDD 分层

每个非 kernel / bootstrap / controlplane 的业务 module 内部按 DDD 三层组织（在 package 上体现，不是物理目录）：

```
com.imsw.observe.<module>/
├── domain/            ← 领域模型 + 领域服务接口（不依赖任何 framework / JPA / Jackson）
├── application/       ← 端口（出站接口） + application service（编排 domain、调 infrastructure）；端口与实现严格分离，端口在此，实现在 infrastructure
└── infrastructure/    ← 端口的实现：JPA repository / 外部 adapter / 第三方集成；内部按子领域聚合（如 alerting 的 alert/evidence/fingerprint/persistence）
```

**业务 module 不单设额外 `interfaces/` 子层**：application 层的 public service / 端口接口本身就是内部 API（被其他 module 或 controlplane 调）。

例外：
- `observe-controlplane`：薄适配器层，只有 `interfaces/`（+ `interfaces/dto/`），无 domain/application。REST controller 直接调 config/alerting/pipeline 的 application service。**不强加业务三层。**（controlplane 的 `interfaces/` 是它的适配器目录，与上面「业务 module 不单设 interfaces」不冲突。）

**controller 契约约定**：
- **返回类型**：必须是 `*Dto`（`interfaces/dto/` 下的 record），由 `Dto.from(domainEntity)` 转换。**禁止** controller 直接返回 domain entity / Po。
- **请求体**（`@RequestBody`）：必须用独立的 `*Request` record，**禁止**用 domain record（`Pipeline`/`Event` 等）或响应 DTO 当请求体（避免 API 契约绑死 domain 结构、domain 无法贴 Bean Validation）。
- **复杂 domain 反序列化**（如 validate/dry-run/saveVersion 接收整个 pipeline 定义）：请求 record 持有 `String xxxJson` 字段，**controller 边界**用 `JsonUtil.fromJson` 反序列化成 domain 后调 service。service 签名保持接收 domain（不感知 HTTP/JSON）。
- **service 返回**：application service 返回 domain entity（非 DTO、非 Po），由 controller 转 DTO。
- `observe-kernel`：不分层，见下方 kernel 条款。
- `observe-bootstrap`：两级结构，见下方 bootstrap 条款。

### 3.2.1 kernel 分包

`observe-kernel`：shared kernel，不分层。按领域聚合分包（`event` / `alert` / `script` / `execution` / `transaction`），领域内端口在 `<domain>/spi`、值对象在 `<domain>/model`；顶层 `error/`（集中共享异常）+ `util/`（TypeConverter、跨 module 复用的 JPA AttributeConverter、统一 `JsonUtil`/`HashUtil` 等工具）。可观测性（trace/metric）用 OTel API，在 `observe-pipeline` 里调，不在 kernel。

### 3.2.2 bootstrap 两级结构

`observe-bootstrap`：composition root，两级结构：

```
com.imsw.observe.bootstrap/
├── main/                 ← @SpringBootApplication、profile 路由、跨进程公共装配
├── worker/               ← worker 进程装配；内部按 SPI 实现归属分子包：
│   ├── db/               ← JdbcDbApi（db binding 的 JDBC 实现，复用 pipeline 事务）
│   ├── source/           ← InMemoryCdcMessageSource（生产 source adapter 占位）
│   └── config/           ← WorkerConfig / WorkerProperties / WorkerShutdown / CoreConfig / AlertingPipelineConfig
├── controlplane/         ← controlplane 进程装配（一期可空壳，留 main 路由口）
└── demo/                 ← DemoMain / DemoPipelineFactory；不带 Spring stereotype，默认 profile 不装配（见下方说明）
```

两级理由：
- 一级（进程层 `main/worker/controlplane`）：贴合「初期同 JVM、未来按 profile 拆进程」的演进路径，进程边界先在包结构上体现。
- 二级（worker 内 SPI 子包）：沿用「按 SPI 实现归属分包」，保留可读性。

**占位实现 vs demo 占位区分**（防误删）：
- **生产占位 adapter**（留 `worker/` 下对应子包）：`InMemoryCdcMessageSource`（真实 MQ 未接入前的 CDC 占位）。生产 profile 装配。可观测性用 OTel API（默认 noop），无占位类；AlertSink/ExecutionRecorder/TransactionOperator/DbApi 均装配真实现（alerting/JDBC），无 noop 兜底（fail-fast：未装配则 Spring 启动失败）。
- **demo 占位**（内嵌在 `DemoMain`，不共享）：`DemoAlertSink`/`DemoTransactionOperator`/`DemoExecutionRecorder`/`DemoDbApi` —— demo 是无 Spring/无 DB 的独立 main，占位自给自足。
- **dry-run 占位**：dry-run 复用生产 DataSource + 事务，事务强制回滚（`DryRunTransactionOperator`，非 noop），`db.*` 真查真执行但回滚。仅 `DryRunService` 内嵌 `NoopExecutionRecorder`（dry-run 不记 execution）。
- **demo 代码**（进 `demo/`）：`DemoMain` / `DemoPipelineFactory`。这些类**不带任何 Spring stereotype**（无 `@Component`/`@Configuration`），`DemoMain` 是独立的 `public static void main` 入口——因此默认 profile 天然不装配，无需 `@Profile`。若未来要让 demo 类进 Spring 装配，**必须**加 `@Profile("demo")` 防止污染默认装配。

### 3.3 resources 目录约定

```
src/main/resources/
├── db/migration/<module>/V<n>__<name>.sql   ← 纯 SQL 脚本（不用 Flyway；按 module 组织，由 DBA / 部署流程手工执行）
└── application[-<profile>].yml               ← 仅 bootstrap module 有
```

---

## 4. 命名规范

### 4.1 包名

固定格式 `com.imsw.observe.<module>.<layer>[.<sub>]`，例：
- `com.imsw.observe.kernel.event`
- `com.imsw.observe.pipeline.domain`
- `com.imsw.observe.pipeline.application`
- `com.imsw.observe.alerting.infrastructure`
- `com.imsw.observe.controlplane.interfaces`（controlplane 用 `interfaces`）

### 4.2 类名

| 概念 | 约定 | 例子 |
|---|---|---|
| 接口 | 不加 `I` 前缀，名词或动名词 | `PipelineRunner`、`AlertSink`、`ExecutionRecorder` |
| 默认实现 | `Default<Interface>` | `DefaultPipelineRunner`、`DefaultAlertSink` |
| JPA 实现 | `Jpa<Interface>` 或直接用具体类名 | `JpaAlertRepository` |
| 内存实现 | `InMemory<Entity>` | `InMemoryDelayedEventStore` |
| 第三方 adapter | `<Vendor><Role>` | `CdcMqSource` |
| 抽象基类 | 仅当有 2+ 实现共享代码时才加 `Abstract` 前缀；只有 1 个实现不要抽象 | `AbstractScriptNode`（暂未需要） |
| 异常 | `<Cause>Exception` | `ScriptExecutionException`、`NodeExecutionException` |
| DTO | `<Concept>Dto` 或 `<Concept>Request` / `<Concept>Response` | `PipelineDto`、`CreatePipelineRequest` |
| Record 值对象 | 直接用领域名词，不加后缀 | `Event`、`AlertSignal`、`NodeSpec` |

### 4.3 方法名

- 动词开头：`create` / `update` / `delete` / `find` / `get` / `execute` / `evaluate` / `emit` / `drain` / `schedule` / `cancel`。
- 布尔返回：`is<X>` / `has<X>` / `can<X>`，不要 `should<X>`。
- 静态工厂：`of(...)` / `from(...)` / `valueOf(...)`。

### 4.4 Groovy 脚本 binding（不可变）

Groovy 脚本能用到的 binding 固定为：`event` / `ctx` / `alerts` / `db` / `now`。

- `db`（`DbApi`）：DB 操作入口，`db.queryOne(sql, params)` / `queryAll` / `update` / `call(spName, params)`。命名参数（`:id`）防注入；SQL 由可信规则作者写，平台只提供执行通道不翻译方言；所有调用跑在当前 pipeline 事务内（Spring 事务上下文）。SP 一期只支持返回结果集（OUT 参数见 roadmap）。脚本处理 `Map`（Groovy Map 语法糖 `row.field` + `as BigDecimal` 走 TypeConverter），不引入实体类。
- 阈值靠脚本硬编码（`thresholds` binding 不在一期）。

新增 binding 必须先在 AGENTS.md / 设计文档登记。

### 4.5 数据库表 / 列名

- 表名：复数 / snake_case（`pipelines`、`pipeline_versions`、`alerts_evidence`）。
- 列名：snake_case（`pipeline_id`、`created_at`、`ends_at`）。
- 时间戳：`alerts` 表用 `starts_at` / `ends_at`（对齐 AlertManager 协议）；其他表用 `started_at` / `ended_at`。
- 状态枚举列：`status VARCHAR` + `CHECK` 约束，不用数据库 enum 类型。
- JSON 列：`LONG VARCHAR` 存 JSON 字符串，列名暗示（如 `labels`、`pipeline_labels`、`annotations`、`definition_json`、`trigger_event`）。

---

## 5. 代码风格

### 5.1 自动格式化（不需要手动管）

- **Spotless** 配 `palantir-java-format`，自动处理：缩进（4 空格）、换行、大括号位置、`import` 顺序。
- 提交前跑 `mvn spotless:apply` 一键 format。
- CI 跑 `mvn spotless:check` 验证。

### 5.2 Checkstyle 兜底（自动 format 处理不了的）

规则在 `build/checkstyle/checkstyle.xml`：

- 不允许 `*` import
- 单文件 ≤ 500 行
- 单方法 ≤ 50 行
- 嵌套 ≤ 4
- 一个文件一个顶层 class
- 命名规范（接口不加 `I` 前缀、常量全大写）
- 不允许 `System.out` / `System.err`（强制用 SLF4J logger）

违规让 build 失败。

### 5.3 注释策略

- **默认不写注释**。命名清楚 + 类型清楚 + 结构清楚 = 不需要注释。
- 写注释时**只写 WHY**：隐藏约束、不直观的设计权衡、workaround 特定 bug。
- **禁止写 WHAT** 注释（"// 创建 pipeline"、"// 检查阈值"）。代码本身已经说了。
- **禁止写引用注释**（"// added for X flow"、"// fixes issue #123"）。这些属于 PR description / commit message。
- Javadoc：public API 接口、有非显然 contract 的方法才写。其他不加。
- 中文 doc / 中文注释 OK（与英文标识符混用），但同一段内容不要中英文都写一遍。

### 5.4 异常处理

自定义异常体系（在 `observe-kernel` 内）：

```
ObserveException (runtime, unchecked)
├── ScriptCompilationException
├── ScriptSandboxException
├── ScriptTimeoutException
├── ScriptExecutionException
├── NodeExecutionException
├── AlertPersistenceException
├── DataSourceException
└── ...
```

规则：
- domain 内抛 `ObserveException` 子类，**不要**抛 `RuntimeException` / `Exception` / `IOException` 等通用异常。
- 不允许吞异常（`catch` 后必须有 `throw` 或 wrap 后 throw）。
- 边界层（controller / source adapter）转译异常：domain 异常 → HTTP 状态码 / metric 计数 / 事务回滚标记。
- 不为不可能的场景加 `try-catch`（信任内部不变量；只有边界 / IO / 用户输入需要）。

### 5.5 注入与可变性

- **构造注入**，不允许字段注入（不要 `@Autowired` 在字段上）。Spring 4.3+ 单构造器自动注入。
- record 优先于 class；record 不要加 setter（不可变）。
- 可变状态只在 `application` / `infrastructure` 层的显式状态对象里存在（如 `ExecutionData.alerts` 列表），且必须用线程安全结构。

---

## 6. 提交规范

### 6.1 格式

```
<type>(<scope>): <subject>

<body 可选，写 WHY>
```

### 6.2 type

`feat` / `fix` / `docs` / `refactor` / `chore` / `perf` / `build`

### 6.3 scope（module 名）

`kernel` / `pipeline` / `alerting` / `config` / `controlplane` / `bootstrap` / `docs` / `build` / `chore`

跨多 module 用 `*`：`refactor(*): ...`

### 6.4 subject

- 祈使句：`add X` 不要 `added X`。
- ≤ 72 字符。
- 不加句号。
- 不加 issue 号（写到 body / PR description）。

### 6.5 body

写**为什么**做这个改动、为什么选这个方案、有什么 tradeoff。不重复 diff 内容。

### 6.6 例子

```
feat(pipeline): add DefaultAlertSink with fingerprint SELECT-then-INSERT

Dedup uses application-layer SELECT-then-INSERT/UPDATE instead of a
unique constraint because Sybase doesn't support partial unique
indexes and we explicitly accept rare duplicate FIRING rows under
extreme concurrency (Grafana shows dupes but operators can identify
them by fingerprint).
```

---

## 7. 文件位置约束

新增任何文件前，先按下表确定位置：

| 内容 | 位置 |
|---|---|
| 领域模型 / 值对象（record） | `observe-<module>/src/main/java/com/imsw/observe/<module>/domain/` |
| application service | `.../<module>/application/` |
| infrastructure 实现（JPA / 外部 adapter） | `.../<module>/infrastructure/` |
| REST controller | `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/` |
| DTO / Request / Response | `observe-controlplane/.../interfaces/dto/` 或 controller 同包 |
| Spring Boot main 类 | `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/main/ObserveApplication.java` |
| `application.yml` | 仅 `observe-bootstrap/src/main/resources/` |
| SQL schema 脚本 | `observe-<module>/src/main/resources/db/migration/<module>/V<n>__<name>.sql`（纯 SQL，不用 Flyway） |
| Groovy 脚本示例 | `docs/examples/pipelines/<name>.groovy` |
| 全局 build 配置（checkstyle / spotless / editorconfig） | 仓库根 + `build/` |
| 设计文档 | `docs/observe-*.md` |
| plan 文档 | `docs/superpowers/plans/YYYY-MM-DD-<topic>-plan.md` |

新 module 必须先在本文档 §3.1 登记，否则不允许创建。

---

## 8. 红线（绝对不做）

- ❌ 引入 Lombok（用 record / 显式构造 / 显式 getter）。
- ❌ 用 `@Autowired` 字段注入。
- ❌ 在 `observe-kernel` 反向依赖任何上层 module。
- ❌ 用数据库外键（除了 PK 的隐式索引）。
- ❌ 加唯一约束（除了 PK）。fingerprint 去重靠应用层 SELECT-then-INSERT。
- ❌ 引入第二个脚本引擎（一期就 Groovy；GraalJS 是远期项）。
- ❌ 引入 JTA / 跨 DataSource 事务。
- ❌ 给 record 加 setter / 把 record 改成可变 class。
- ❌ 写 `// TODO` / `// FIXME` 占位代码并提交（要么实现，要么不写；如必须标记未完成，写到 roadmap 或 plan 文件）。
- ❌ 改 module 结构 / pom 依赖关系但不更新本文档 §3.1 与 §4.1。
- ❌ 用 `System.out` / `System.err` / `printStackTrace()`（用 SLF4J）。
- ❌ 在 `domain` 层依赖 Spring framework / JPA annotation / Jackson annotation（domain 保持纯净）。
- ❌ 在 `domain` record 里加业务方法时调 infrastructure 接口（domain 只调 domain）。
- ❌ 不写设计文档就开干；不读设计文档就改设计。

### 8.1 已登记的例外

- **kernel 依赖 `jakarta.persistence-api`**：kernel 允许且仅允许依赖 `jakarta.persistence-api`（JPA 标准 API 接口，非运行时实现）+ `jackson-databind` + `jackson-datatype-jsr310`，用于承载跨 module 复用的 JPA `AttributeConverter`（`MapStringObjectToJsonConverter` / `MapStringStringToJsonConverter`，在 `kernel/util/`）和统一 `JsonUtil`（正确配置 JavaTimeModule，全仓库复用）。**仅限 API 接口依赖，不允许引入任何 JPA 实现 / Spring Data / Hibernate**；kernel 内不允许出现 `@Entity` Po（`@Entity` 留在各业务 module 的 infrastructure）。此例外为换取 DRY（去重 alerting 与 config 重复 converter/JSON 处理），已显式登记，不视为破坏 kernel 纯净。
- **observe-pipeline 依赖 `opentelemetry-api`**：pipeline 允许且仅允许依赖 `opentelemetry-api`（OTel 纯 API jar，默认 noop，非运行时 SDK），用于在 `DefaultPipelineRunner` 等热路径发 trace/metric。**仅限 API**，SDK / exporter（`opentelemetry-sdk` / OTLP / Prometheus）留给 bootstrap 装配，不在 pipeline。版本由 `spring-boot-dependencies` BOM 托管。**kernel 不引 OTel**（零依赖红线不动）；可观测性调用点只在 pipeline，不在 kernel。此例外为对齐 OTel 工业标准、避免自造 SPI 适配层，已显式登记。

---

## 9. 弱化测试

本项目当前弱化测试要求：

- 不要求单元测试覆盖率。
- 不强制 TDD。
- AI 协作者**不要主动**给业务代码加大量单元测试。
- 关键算法 / 有明显边界场景的核心组件（如 `FingerprintCalculator`、`Condition` 求值器、`TypeConverter`）建议有少量 sanity test 验证行为，但不阻塞交付。
- 集成测试在 P3 / P5 阶段随端到端 demo 一起补，不强求每个 PR 都跑通。

未来若团队 / 业务对质量要求提高，重新评估测试策略。

---

## 10. AI 协作流程

每次在本项目内开始写代码 / 改代码前：

1. **读完本文档** + 当前任务相关的 `docs/observe-platform-design.md` 章节。
2. **确认范围**：本次改动在 P0-P5 哪个阶段？是否在一期范围内？超范围的回去找用户对齐。
3. **看 plan**：当前阶段有 plan 文件则按 plan 步骤执行；plan 缺失或与设计冲突时**先停下来问用户**。
4. **遵循 §3 文件位置**：新文件放对地方。
5. **遵循 §5 代码风格**：写完跑 `mvn spotless:apply checkstyle:check`。
6. **遵循 §6 提交规范**：commit message 格式不能错。
7. **不要超范围**：plan 没要求的"顺手改"不要做；发现设计 / 代码不一致，写到 plan 或问用户，不要私自改。

---

## 引用文档

- 设计：[`docs/observe-platform-design.md`](docs/observe-platform-design.md)
- 路线 / 二期项 / 实施阶段：[`docs/observe-roadmap.md`](docs/observe-roadmap.md)
- P0 plan：[`docs/superpowers/plans/2026-07-11-observe-p0-scaffold-plan.md`](docs/superpowers/plans/2026-07-11-observe-p0-scaffold-plan.md)
