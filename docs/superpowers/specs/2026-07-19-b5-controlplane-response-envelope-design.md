# B5 Control-plane 统一响应信封设计

**日期**：2026-07-19
**状态**：Proposed
**批次**：B5（API 地基，前端开工前置）
**关联**：`docs/2026-07-19-controlplane-api-audit.md`（缺口来源）、`docs/2026-07-19-design-and-data-model-review.md`、`docs/adr/0002-namespace-top-level-isolation.md`、`docs/superpowers/specs/2026-07-18-observe-refactor-batches-design.md`

> 本 spec 只覆盖 **B5（统一响应信封 + 分页 + 错误体系 + 参数校验）**。后续 B6（看板查询接口）、B7（ADR-0005 全套）、B8（小修小补）各自单独 spec。

---

## 1. 背景与目标

`docs/2026-07-19-controlplane-api-audit.md` 发现 control-plane 现有 21 个 REST 端点**无统一响应信封**：控制器裸返回 record / `List` / `ResponseEntity`，404 空 body，错误只有 `{"error":"msg"}` 无人机可读 code，列表无真分页，请求体无 `@Valid` 校验。这使前端无法用统一拦截器解包、无法靠 code 区分错误类型、无法分页翻页。

**B5 目标**：在不改业务逻辑、不改 DTO 字段、不改 service 业务行为的前提下，把 control-plane 所有 REST 接口统一到三态信封，并补齐分页、错误码、参数校验。B5 是 B6/B7 新接口的格式地基，也是前端开工前置。

**非目标**：
- 不新增聚合/时间序列/统计接口（→ B6）。
- 不动告警 schema / 状态机 / ack/resolve（→ B7）。
- 不改 pipeline/alerting/config 模块的领域与业务代码。
- 不引入认证/授权/CORS（一期不做，另议）。

---

## 2. 设计决策（已与用户确认）

| 决策 | 选择 |
|---|---|
| 响应信封形态 | `{data}` / `{data,page}` / `{error:{code,message,traceId}}` 三态 |
| 404 body | 走 `error` body（不再空 body） |
| 分页策略 | `?page=&size=` 偏移分页，默认 1/20；`?limit=` 作为 size 别名兼容 |
| 错误码体系 | `NOT_FOUND` / `VALIDATION` / `BAD_REQUEST` / `CONFLICT` / `INTERNAL` |
| 参数校验 | 请求体 record 字段加 bean-validation 约束，controller 加 `@Valid` |
| 落地方式 | 全量替换所有现有端点返回类型（含改 15 个 ControllerTest 断言） |
| service 分页签名 | `findAlerts/findExecutions/findFailedExecutions` 改接受 `Pageable`、返回 Spring `Page<T>` |
| 信封类放置 | 全部放 `observe-controlplane`（web 表现层关切，不污染 kernel） |

---

## 3. 信封形态规范

### 3.1 单资源
```jsonc
{ "data": { "...dto": "..." } }
```

### 3.2 列表带分页
```jsonc
{
  "data": [ {"...dto":""} ],
  "page": { "page": 1, "size": 20, "total": 137 }
}
```

### 3.3 列表无分页（数据量天然小：versions / namespaces / 单 namespace 的 pipelines·subscriptions）
```jsonc
{ "data": [ {"...dto":""} ] }
```

### 3.4 错误（所有错误，含 404）
```jsonc
{
  "error": {
    "code": "NOT_FOUND",
    "message": "alert 123 not found in namespace ops",
    "traceId": "a1b2c3..."
  }
}
```

### 3.5 字段语义
- `data`：成功时的业务载荷。单资源是对象，列表是数组。
- `page`：仅分页列表出现。`page` 为当前页（1-based），`size` 为页大小，`total` 为总条数。
- `error.code`：机器可读枚举（§5.1）。
- `error.message`：人类可读，可直接展示。
- `error.traceId`：串联日志，从 MDC 取；空则生成 UUID 并回写 MDC。

---

## 4. 组件设计

全部新增在 `observe-controlplane/src/main/java/com/imsw/observe/controlplane/`。

### 4.1 信封类（`interfaces/web/`）

```
interfaces/web/ApiResponse.java       — 单资源 + 无分页列表信封
interfaces/web/PageResponse.java      — 分页列表信封
interfaces/web/Page.java              — record {int page; int size; long total;}
interfaces/web/ErrorBody.java         — record {String code; String message; String traceId;}
interfaces/web/ErrorCode.java         — 枚举（见 §5.1）
interfaces/web/ResourceNotFoundException.java — 携带 code+message 的 RuntimeException
interfaces/web/ErrorResponseException.java    — 通用：携带 ErrorCode + HttpStatus（B7 复用）
```

**`ApiResponse<T>`**：
```java
public record ApiResponse<T>(T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(data); }
}
```

**`PageResponse<T>`**：
```java
public record PageResponse<T>(List<T> data, Page page) {
    public static <T> PageResponse<T> of(List<T> data, int page, int size, long total) {
        return new PageResponse<>(data, new Page(page, size, total));
    }
}
```

**`Page`**：`public record Page(int page, int size, long total) {}`

**`ErrorBody`**：`public record ErrorBody(String code, String message, String traceId) {}`

**`ErrorCode`**（见 §5.1）。

**`ResourceNotFoundException`**：继承 `RuntimeException`，构造接 `(ErrorCode code, String message)`；默认 code=`NOT_FOUND`。

**`ErrorResponseException`**：继承 `RuntimeException`，构造接 `(HttpStatus status, ErrorCode code, String message)`；供业务层抛业务错误（B7 ack/resolve、重复键冲突等复用）。

### 4.2 改造 `GlobalExceptionHandler`（`config/`）

从 4 个 handler 扩到 9 个，全部返回 `ResponseEntity<ErrorBody>`（不再 `Map<String,String>`）：

| 异常 | HTTP | code |
|---|---|---|
| `ResourceNotFoundException` | 404 | `NOT_FOUND` |
| `ErrorResponseException` | `ex.status()` | `ex.code()` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION` |
| `ConstraintViolationException` | 400 | `VALIDATION` |
| `MethodArgumentTypeMismatchException` | 400 | `BAD_REQUEST` |
| `HttpMessageNotReadableException` | 400 | `BAD_REQUEST` |
| `MissingServletRequestParameterException` | 400 | `BAD_REQUEST` |
| `IllegalArgumentException` | 400 | `BAD_REQUEST`（保留兼容现有 service 手写校验） |
| `RuntimeException` / `ObserveException` | 500 | `INTERNAL` |

- `VALIDATION` 的 message 聚合所有字段错误：`"name: must not be blank; team: must not be blank"`。
- 每条 ErrorBody 注入 `traceId`：`MDC.get("traceId")`，空则 `UUID.randomUUID()` 并 `MDC.put`。
- 兜底 `RuntimeException` 不泄露堆栈到 message（只 `e.getClass().getSimpleName()` + 受控 message）。

### 4.3 控制器改造（`interfaces/`）

7 个 controller 的返回类型映射：

| 现返回 | 新返回 |
|---|---|
| `T`（create/update/submit 等） | `ApiResponse<T>` |
| `ResponseEntity<T>`（单资源 get） | `ApiResponse<T>`（查不到抛 `ResourceNotFoundException`） |
| `List<T>`（分页列表：alerts/executions/failed-executions） | `PageResponse<T>` |
| `List<T>`（无分页列表：pipelines/subscriptions/versions/namespaces） | `ApiResponse<List<T>>` |
| `void`（archive/delete） | `ApiResponse<Void>`（`ok(null)` → `{}`，或显式空 200） |

- 单资源查不到：`orElseThrow(() -> new ResourceNotFoundException("..."))` 替代 `notFound().build()`。
- 分页列表：controller 接 `@RequestParam page/size`（带默认值 1/20 + sanitize），构造 `PageRequest`，service 返回 Spring `Page`，转 `PageResponse.of(...)`。
- `?limit=` 兼容：`size` 缺省时若传了 `limit`，`size = limit`。

### 4.4 service 层分页签名改造（`observe-pipeline` / `observe-alerting`）

仅 3 个方法（B5 唯一动 service 层之处，属查询能力扩展非业务变更）：

```
AlertQueryService.findAlerts(namespace, status, team, pipelineId, Pageable) → Page<AlertEntity>
ExecutionQueryService.findExecutions(namespace, pipelineId, Pageable)       → Page<Execution>
ExecutionQueryService.findFailedExecutions(namespace, pipelineId, Pageable) → Page<FailedExecution>
```

- 实现内 `PageRequest.of(0, safeLimit)` 改用传入 `Pageable`。
- 返回 Spring `Page`（带 `totalElements`）。
- 对应 repository 查询：若现用 `findAll(Sort)` + subList，改成 `findAll(Pageable)` 或自定义 `@Query` count + list。
- 同模块 service 单测同步改分页断言。

**不动的 service**：`findById/findExecution/findFailedExecution`（单条，仍返回 `Optional`）、`*CrudService`（CRUD 业务）、`VersionPublishService`。

---

## 5. 错误码体系

### 5.1 `ErrorCode` 枚举与 HTTP 映射

| ErrorCode | HTTP | 触发场景 |
|---|---|---|
| `NOT_FOUND` | 404 | 资源不存在 / namespace 不匹配 |
| `VALIDATION` | 400 | bean-validation 校验失败 |
| `BAD_REQUEST` | 400 | 参数类型错 / JSON 不可读 / 缺必填 query param / `IllegalArgumentException` |
| `CONFLICT` | 409 | 业务冲突（重复业务键、版本已发布）—— B5 预留，B7 落地 |
| `INTERNAL` | 500 | 未预期异常兜底 |

枚举带 `httpStatus` 字段，`GlobalExceptionHandler` 统一从枚举取 status。

### 5.2 traceId 策略
- 优先 `MDC.get("traceId")`（OTel 埋点若已注入则复用）。
- 空则 `UUID.randomUUID().toString()` 并回写 MDC（日志可串联）。

---

## 6. 参数校验落地

**前置依赖**：仓库当前**无 `jakarta.validation` 任何使用**，`spring-boot-starter-validation` 也**未**作为依赖（Spring Boot 4.1 的 starter-web 不再传递它）。B5 须先在 `observe-controlplane/pom.xml` 加：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```
（其余模块若将来需要再各自加，本期仅 controlplane。）

- 请求体 record 字段加 bean-validation 约束：
  - `CreatePipelineRequest.name` `@NotBlank`、`team` `@NotBlank`、`application` `@NotBlank`
  - `CreateSubscriptionRequest` 内 `SubscriptionFields` 必填字段 `@NotNull`
  - `CreateNamespaceRequest.name` `@NotBlank`
  - `SubmitEventRequest.source` `@NotBlank`（现有手写 `if (source==null||...) throw` 删除）
  - `SaveVersionRequest.pipelineJson` `@NotBlank`、`DryRunRequest.*` `@NotBlank`
- controller 方法 body 参数加 `@Valid`。
- controller 类加 `@Validated`（若需 path/query param 约束，如 `@Min` on page）。
- `page` `@Min(1)`、`size` `@Min(1) @Max(500)`（sanitize 上限防超大查询）。
- 失败统一 → `VALIDATION` 400。

---

## 7. 测试

### 7.1 现状澄清（影响测试策略）
- 现有 controller 测试只有 **3 个文件**：`NamespaceControllerTest`、`EventControllerTest`、`ValidateControllerTest`（共 15 个 `@Test`）。
- 它们是 **plain Mockito 单测**：直接 `new XxxController(mockService)`、调方法、断言返回对象（**无 MockMvc、无 jsonPath**）。
- `AlertController` / `ExecutionController` / `PipelineController` / `SubscriptionController` **当前无 controller 层测试**（service 层有 `*CrudServiceTest` / `*QueryServiceTest` 覆盖业务）。
- `GlobalExceptionHandler` 当前**零测试覆盖**。

### 7.2 改现有 3 个 controller 单测（断言解包 `.data()`）
返回类型从 `XxxDto` / `List<XxxDto>` 变成 `ApiResponse<T>` 后，现有断言要解包：
- `assertEquals(dto, controller.create(...))` → `assertEquals(dto, controller.create(...).data())`
- `List<XxxDto>` 返回 → `.data()` 取出再断言
- 涉及：`NamespaceControllerTest`、`EventControllerTest`、`ValidateControllerTest`
- 注意 `EventController.submit` 现有 `@ResponseStatus(ACCEPTED)` 语义不变，只包信封。

### 7.3 新增 `GlobalExceptionHandlerWebTest`（MockMvc，本 repo 新模式）
错误处理必须经 Spring MVC 栈才能触发（`@Valid`、type mismatch、JSON 不可读都是框架层），故用 **MockMvc slice 测试**（`@WebMvcTest` + `@Import(GlobalExceptionHandler)` + mock service）。
- 404：controller 抛 `ResourceNotFoundException` → 响应 404 + `$.error.code=NOT_FOUND` + `$.error.message` 非空 + `$.error.traceId` 非空（验证**不再是空 body**）
- `@Valid` 失败（空体 POST）→ 400 + `$.error.code=VALIDATION` + message 聚合字段错误
- JSON 不可读（malformed body）→ 400 + `$.error.code=BAD_REQUEST`
- path param 类型错（`GET /alerts/abc`）→ 400 + `$.error.code=BAD_REQUEST`
- 未预期异常（mock service 抛 RuntimeException）→ 500 + `$.error.code=INTERNAL` + `traceId` 非空
- MockMvc 在本 repo 是首次引入，需确认 `observe-controlplane` pom 已含 `spring-boot-starter-test`（含 MockMvc）；若无依赖则补。

### 7.4 分页测试
- service 单测（`AlertQueryServiceTest` / `ExecutionQueryServiceTest`）：传 `Pageable`，断言返回 Spring `Page` 的 `totalElements` 与分页内容正确。
- 可选 controller MockMvc 抽查：`?page=2&size=5` → `$.page.total`、`$.data` 仅含对应页；`?limit=10` 等同 `size=10`；`size` 超上限被 sanitize。

### 7.5 service 单测同步
`AlertQueryService` / `ExecutionQueryService` 分页签名改动后，对应单测断言同步改（调新签名）。

---

## 8. 验收标准（批末门禁）

1. `mvn compile` 全绿
2. `mvn test` 全绿（改过的 controller 测试 + 新增信封/分页测试）
3. `mvn checkstyle:check` 全绿
4. `mvn spring-boot:run`（profile `all-in-one`）可启动
5. 手动 curl 抽查：
   - `GET /api/v1/alerts?namespace=ops` → `{data:[...],page:{page:1,size:20,total:N}}`
   - `GET /api/v1/alerts/999999?namespace=ops` → 404 `{error:{code:NOT_FOUND,...}}`
   - `POST /api/v1/namespaces`（空体）→ 400 `{error:{code:VALIDATION,...}}`
   - `GET /api/v1/alerts/abc?namespace=ops` → 400 `{error:{code:BAD_REQUEST,...}}`
   - `GET /api/v1/alerts?namespace=ops&page=2&size=5` → `$.page` 正确

---

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| service 分页签名改动波及已有 service 单测 | 直接改测试（全量替换已认可），不留 `@Deprecated` 过渡 |
| 前端兼容 | 前端尚未开工，正是统一的好时机，无兼容包袱 |
| Checkstyle 卡新类 import/格式 | 新类按现有风格，提交前 `mvn checkstyle:check` 自查 |
| `?limit=` 旧调用方 | 作为 size 别名兼容，不破 |
| 兜底 500 泄露堆栈 | message 只放受控内容 + 类名，不 dump stack |

---

## 10. 与后续批次的接口

- **B6（看板查询接口）**：所有新接口（`/stats/*`、时间序列）直接用 `ApiResponse`/`PageResponse` + `ErrorCode`。
- **B7（ADR-0005）**：ack/resolve/silence 写接口的业务冲突用 `ErrorResponseException(CONFLICT, ...)`。
- **B8（小修小补）**：独立，不依赖 B5。

---

## 附录：改动文件清单（预估）

**新增（controlplane）**：
- `interfaces/web/ApiResponse.java`、`PageResponse.java`、`Page.java`、`ErrorBody.java`、`ErrorCode.java`、`ResourceNotFoundException.java`、`ErrorResponseException.java`
- `GlobalExceptionHandlerWebTest.java`
- `observe-controlplane/pom.xml` 加 `spring-boot-starter-validation` 依赖

**改造（controlplane）**：
- `config/GlobalExceptionHandler.java`（4→9 handler）
- `interfaces/AlertController.java`、`ExecutionController.java`、`NamespaceController.java`、`PipelineController.java`、`SubscriptionController.java`、`EventController.java`、`ValidateController.java`
- 上述 controller 的嵌套 request record（加 bean-validation 约束）
- 3 个现有 controller 单测（`Namespace`/`Event`/`Validate`）：断言解包 `.data()`

**新增测试**：
- `GlobalExceptionHandlerWebTest`（MockMvc slice，repo 首次引入）
- service 分页单测补充（`AlertQueryServiceTest` / `ExecutionQueryServiceTest`）

**依赖确认**：`observe-controlplane` pom 是否含 `spring-boot-starter-test`（提供 MockMvc + bean-validation），缺则补。

**改造（pipeline / alerting，service 分页）**：
- `AlertQueryService`（+ 实现）、`ExecutionQueryService`（+ 实现）、对应 repository 查询
- 对应 service 单测
