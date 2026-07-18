# Control-plane API 审计与前端就绪度

**日期**：2026-07-19
**状态**：Review（待评审）
**目的**：评估 `observe-controlplane` REST API 是否足以支撑即将开发的前端（看板/图表/CRUD），并给出**统一格式规范**与**缺口补齐建议**，作为前端开工前的地基。

> 本文档与 `2026-07-19-frontend-page-plan.md` 配套：本文档讲「后端接口现状 + 要补什么」，那边讲「前端要做什么页面」。

---

## 0. TL;DR

- **CRUD 完整**：pipeline / subscription / namespace 增删改查齐全（pipeline 用 `archive` 软删）。
- **⚠️ 无统一响应信封**：控制器裸返回 record / `List` / `ResponseEntity`，404 空 body，错误只有 `{"error":"msg"}`。**前端开工前必须先统一**（见 §3）。
- **⚠️ 看板/图表几乎做不了**：无聚合接口、无时间序列、无时间范围筛选、无 severity 筛选、无真分页。**这是前端最核心价值，必须补**（见 §4）。
- 建议顺序：**先统一信封 → 再补聚合/统计接口 → 再补筛选+分页 → 然后前端开搭脚手架**。

---

## 1. 现有接口清单（21 个端点）

### 1.1 Namespace（`/api/v1/namespaces`）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/namespaces` | 创建（body: name, displayName） |
| GET | `/namespaces` | 列表（无分页） |
| GET | `/namespaces/{name}` | 详情（404 空 body） |
| PUT | `/namespaces/{name}` | 更新 displayName |
| DELETE | `/namespaces/{name}` | 删除 |

### 1.2 Pipeline（`/api/v1/namespaces/{namespace}/pipelines`）— 按业务键寻址
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `.../pipelines` | 创建（team, application, labels, name, description, createdBy） |
| GET | `.../pipelines` | 列表 |
| GET | `.../pipelines/{name}` | 详情 |
| PUT | `.../pipelines/{name}` | 更新（复用 create body） |
| POST | `.../pipelines/{name}/archive` | **软删**（无硬删 DELETE） |
| POST | `.../pipelines/{name}/versions` | 保存版本 |
| GET | `.../pipelines/{name}/versions` | 版本列表 |
| POST | `.../pipelines/{name}/versions/{v}/publish` | 发布版本 |
| POST | `.../pipelines/{name}/versions/{v}/archive` | 归档版本 |

### 1.3 Subscription（`/api/v1/namespaces/{namespace}/subscriptions`）
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `.../subscriptions` | 创建 |
| GET | `.../subscriptions` | 列表 |
| GET | `.../subscriptions/{name}` | 详情 |
| PUT | `.../subscriptions/{name}` | 更新 |
| DELETE | `.../subscriptions/{name}` | 删除（硬删） |

### 1.4 Alert（`/api/v1/alerts`）— 必带 `?namespace=`
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/alerts?namespace=&status=&team=&pipeline_id=&limit=` | 列表（仅这些筛选，默认 100） |
| GET | `/alerts/{id}?namespace=` | 详情 |
| GET | `/alerts/{id}/evidence?namespace=` | 证据（1:1） |

### 1.5 Execution（`/api/v1`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/executions?namespace=&pipeline_id=&limit=` | 成功/短路执行 |
| GET | `/executions/{id}?namespace=` | 详情 |
| GET | `/failed-executions?namespace=&pipeline_id=&limit=` | 失败执行 |
| GET | `/failed-executions/{id}?namespace=` | 详情 |

### 1.6 工具接口
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/events` | 提交事件（202，返回 eventId） |
| POST | `/api/v1/validate/pipeline` | 校验 pipeline JSON |
| POST | `/api/v1/validate/dry-run` | 干跑（pipeline + event） |

---

## 2. 响应格式一致性评估

| 维度 | 现状 | 问题 |
|---|---|---|
| 成功响应 | 裸 record / `List<record>` / `ResponseEntity<record>` | 无信封，前端无法统一拦截 |
| 列表分页 | 只有 `?limit=`，无 page/offset/cursor/total | 无法分页、无法知道是否截断 |
| 404 | `ResponseEntity.notFound().build()`，**空 body** | 前端只能靠 HTTP 状态，拿不到错误信息 |
| 错误 | `GlobalExceptionHandler` 统一返回 `{"error":"msg"}` | 只有人类可读 msg，**无机器 code**；且 404 不走它 |
| 时间 | 统一 `Instant` ISO-8601 UTC | ✅ 一致 |
| 参数校验 | 无 `@Valid`/`@NotNull`，裸 record | 缺字段会 NPE→500 |
| 业务键 vs id | API 全按 `namespace+name`，BIGINT id 不暴露 | ✅ 一致（符合 ADR-0003） |

---

## 3. 建议：统一响应规范（前端开工前置）

### 3.1 统一信封

```jsonc
// 单资源
{ "data": { ...dto } }

// 列表（带分页）
{ "data": [ ...dto ], "page": { "page": 1, "size": 20, "total": 137 } }

// 错误（所有错误，含 404）
{ "error": { "code": "NOT_FOUND", "message": "pipeline not found: ns/foo", "traceId": "..." } }
```

要点：
- **`data` 包裹**：所有成功响应统一进 `data`。列表的 `page` 可选（无分页的列表可不带）。
- **`error` 带 `code`**：机器可读（`NOT_FOUND`/`VALIDATION`/`CONFLICT`/`INTERNAL`），`message` 给人看，`traceId` 便于排查。
- **404 也走异常**：控制器抛 `ResourceNotFoundException`（新增），由 `GlobalExceptionHandler` 转 404 + `error` body，不再 `notFound().build()`。
- **分页统一**：列表端点加 `?page=&size=`（默认 1/20），返回 `page.total`。`limit=` 作为 size 别名兼容。
- **参数校验**：请求体加 `@Valid` + record 字段加 `@NotBlank`/`@NotNull`，`MethodArgumentNotValidException` → 400 `VALIDATION`。

### 3.2 实现改动量（估）
- 新增 `ApiResponse<T>`、`PageResponse`、`ErrorBody`、`ResourceNotFoundException`（kernel 或 controlplane 内）。
- 改 `GlobalExceptionHandler`：加 404/校验/type-mismatch/不可读 JSON 几个 handler。
- 各控制器返回类型从 `XxxDto`/`List<XxxDto>` 改成 `ApiResponse<XxxDto>`/`PageResponse<XxxDto>`（机械替换）。
- 不改业务逻辑、不改 DTO 字段。

> 这是纯横切改造，建议作为下一个批次（B5-API-envelope）单独做，前端在它落地后再开工。

---

## 4. 看板/图表缺口（前端核心价值，必须补）

### 4.1 缺口清单

| 缺口 | 影响 | 建议接口 |
|---|---|---|
| **聚合统计** | 看板头部卡片数（今日告警数、FIRING 数、成功率）做不出 | `GET /api/v1/stats/alerts?namespace=&from=&to=` 返回按 severity/status 的计数 |
| **时间序列** | 折线/柱状图（告警趋势、执行吞吐）做不出 | `GET /api/v1/stats/alerts/timeseries?namespace=&from=&to=&bucket=1h` 返回 `[{bucketStart, count}]` |
| **执行成功率** | manager 看板核心指标 | `GET /api/v1/stats/executions?namespace=&from=&to=` 返回 success/failed/short_circuited 计数 + 成功率 |
| **时间范围筛选** | 所有列表都做不了「最近 24h」 | alert/execution 列表加 `?from=&to=` |
| **severity 筛选** | 「只看 CRITICAL」做不了 | `/alerts` 加 `?severity=` |
| **真分页** | 列表无法翻页/无限滚动 | 列表加 `?page=&size=` + total（见 §3.1） |
| **告警写操作** | 无法在前端 ack/resolve/silence | `POST /alerts/{id}/ack`、`POST /alerts/{id}/resolve`、`POST /silences`（依赖 ADR-0005 落地） |
| **失败重试** | 运维无法一键重跑失败执行 | `POST /failed-executions/{id}/retry` |
| **版本 diff** | pipeline「这版改了啥」做不出 | `GET .../versions/{v1}/diff/{v2}` |

### 4.2 优先级（按前端 MVP 需要）
- **P0（看板 MVP 必须）**：alert/execution 聚合 + 时间序列 + 时间范围筛选。
- **P1（告警处理必须）**：severity 筛选 + 真分页 + 告警 ack/resolve。
- **P2（运维增强）**：失败重试、版本 diff、silence。
- **P3（对外宣传）**：跨 namespace 汇总（与 ADR-0002 软隔离冲突，需单独决策）。

### 4.3 统计接口示例规范（与 §3 信封一致）

```jsonc
GET /api/v1/stats/alerts?namespace=ops&from=2026-07-19T00:00:00Z&to=2026-07-19T23:59:59Z

{
  "data": {
    "namespace": "ops",
    "from": "2026-07-19T00:00:00Z",
    "to":   "2026-07-19T23:59:59Z",
    "bySeverity": { "CRITICAL": 12, "WARNING": 47, "INFO": 5 },
    "byStatus":   { "FIRING": 8, "RESOLVED": 56 },
    "total": 64
  }
}
```

```jsonc
GET /api/v1/stats/alerts/timeseries?namespace=ops&from=...&to=...&bucket=1h

{
  "data": [
    { "bucketStart": "2026-07-19T00:00:00Z", "count": 3 },
    { "bucketStart": "2026-07-19T01:00:00Z", "count": 1 }
  ]
}
```

---

## 5. 其它前端就绪项

- **CORS**：controlplane 模块未见 `WebMvcConfigurer`/`@CrossOrigin`。前端浏览器直连前需确认 CORS 在 bootstrap/gateway 层配置，否则开发期用 vite proxy 转发。
- **认证**：当前无 auth 层。前端如需多用户（pipeline 维护者 vs 告警处理者 vs manager），需要先定认证/授权方案（一期 ADR 明确 RBAC 是远期，但页面层至少要能切 namespace scope）。
- **告警实时性**：当前只有拉取（列表/详情），无 WebSocket/SSE。看板若要「实时刷新」，前端轮询即可（一期够用），实时推送是远期。

---

## 6. 结论与建议批次

建议把后端补齐拆成一个批次 **B5-controlplane-frontend-ready**：

1. 统一响应信封 + 分页 + 错误码 + 404 body（§3）。
2. 时间范围筛选 + severity/status 筛选（§4 P0/P1）。
3. 聚合 + 时间序列接口（§4 P0）。
4. （可选）告警 ack/resolve（依赖 ADR-0005 决策，见 design-review §3）。

前端在 B5 信封 + P0 落地后即可开工搭脚手架与看板页。
