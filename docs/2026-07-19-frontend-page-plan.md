# 前端页面规划（3 类用户）

**日期**：2026-07-19
**状态**：Review（待评审）
**前端工程**：`a-solid-ui-template`（独立仓库，Vite + React + shadcn/ui，偏向打进 Spring Boot 静态目录 — 见记忆 [[project-frontend-scaffold]]）
**配套文档**：`2026-07-19-controlplane-api-audit.md`（后端接口现状与缺口）

> 本文按「谁用、要看什么、要做什么」组织页面，不写实现细节。每页标注**依赖的接口**，缺的接口指向 API 审计 §4 的 P0/P1。

---

## 0. 用户角色与核心场景

| 角色 | 一句话定位 | 主要动作 |
|---|---|---|
| **A. Pipeline/Subscription 维护者** | 配置「监测什么、怎么测」的工程角色 | CRUD pipeline / subscription、版本管理、校验/干跑、上线发布 |
| **B. 告警查看处理者** | 收到告警、判断、处置的值班/运维角色 | 看告警列表、查证据、ack/resolve、查执行历史、排障 |
| **C. Manager / 对外宣传** | 看整体大盘、向外部展示价值的管理/汇报角色 | 看汇总指标、趋势图、成功率、对外演示 demo |

> 一期无 RBAC（ADR 远期）。三角色通过 **namespace scope 切换 + 页面分组**区分，不做硬权限拦截。同一人可能身兼多角色，页面用导航分区。

---

## 1. 全局骨架

- **顶部导航**：namespace 切换器（`GET /namespaces`）+ 角色页签（配置 / 告警 / 大盘）。
- **响应规范**：所有页面消费统一信封 `{data}` / `{error}`（见 API 审计 §3，前端开工前置）。
- **技术栈**：React + Vite + shadcn/ui + Tailwind；图表用 Recharts 或 ECharts；表格用 TanStack Table。

---

## 2. 角色 A — Pipeline / Subscription 维护者

### A1. Pipeline 列表页
- 表格：name / team / application / status(DRAFT/PUBLISHED/ARCHIVED) / currentVersion / updatedAt
- 操作：新建、查看、编辑、归档、管理版本
- **接口**：`GET .../pipelines`（需加分页 P1）

### A2. Pipeline 编辑器（核心页）
- 左：pipeline JSON/可视化编辑（节点：check / emit / script 等）
- 右：**校验 + 干跑** 实时面板
  - 「校验」：`POST /validate/pipeline` → 错误列表
  - 「干跑」：选一个示例 event，`POST /validate/dry-run` → 展示 outcome + 每节点输出 + 触发的告警
- 底部：版本操作（保存版本 / 发布 / 归档 / 查看历史）
- **接口**：`POST .../versions`、`POST .../versions/{v}/publish`、`GET/POST /validate/*` ✅ 已有

### A3. Pipeline 版本管理页
- 版本列表（版本号 / status / definitionHash / publishedBy / 时间）
- **版本 diff**（某版 vs 当前）：`GET .../versions/{v1}/diff/{v2}` ← **缺（P2）**
- 一键回滚到某版（发布旧版）

### A4. Subscription 列表页
- 表格：name / sourceType(CDC/CRON/API) / pipeline / actionType / cronExpression / status
- 按 sourceType 分组或筛选；CRON 订阅要能直接看到表达式
- **接口**：`GET .../subscriptions` ✅

### A5. Subscription 编辑页
- 表单：选 pipeline + 版本 → 配置源（CDC: mq/topic/db/table/opTypes；CRON: cronExpression/concurrent；API: apiName）→ fieldFilter 条件编辑器（AND/OR/Compare/In 树形）→ actionType(RUN/SCHEDULE/CANCEL)
- **条件编辑器**是这页的重点：把 `Condition` sealed 树可视化编辑
- **接口**：`POST/PUT .../subscriptions` ✅

### A6. 触发测试页（可选）
- 手动提交一个事件测试链路：`POST /api/v1/events` ✅ → 跳转到执行详情看结果

---

## 3. 角色 B — 告警查看处理者

### B1. 告警列表页（值班首屏）
- **筛选条**：severity（CRITICAL/WARNING/INFO）+ status（FIRING/RESOLVED）+ team + pipeline + 时间范围
- 表格：severity 徽标 / fingerprint / labels.entity / startsAt / lastSeenAt / dedupCount / status
- FIRING + CRITICAL 置顶高亮
- **接口**：`GET /alerts` + 需要 `?severity=` + `?from=&to=` ← **缺（P0/P1）**

### B2. 告警详情页（排障核心）
- 顶部：告警元信息（severity / labels / annotations / 时间线 starts→lastSeen→ends→resolved）
- **证据区**：`GET /alerts/{id}/evidence` → 展示节点输出（JSON/表格）✅
- **关联执行**：跳到 executionId 对应的执行详情
- **处置动作**：ack / resolve / （silence）← **写接口缺（P1，依赖 ADR-0005）**
- 告警历史/状态流转时间线（dedup 演化）← **缺（P1）**

### B3. 执行历史页
- 筛选：pipeline + status(SUCCESS/SHORT_CIRCUITED) + 时间范围
- 表格：triggerType / triggerEvent 摘要 / status / durationMs / startedAt
- **接口**：`GET /executions` + 需要 status 筛选 + 时间范围 ← **缺（P0/P1）**

### B4. 失败执行页（排障）
- 表格：errorType 徽标 / nodeName / errorMessage / status(PENDING/RESOLVED/IGNORED) / createdAt
- 详情：stackTrace 查看 + **一键重试** `POST /failed-executions/{id}/retry` ← **缺（P2）**
- **接口**：`GET /failed-executions` ✅

### B5. （可选）实时刷新
- 列表页轮询（一期），或 SSE 推送（远期）

---

## 4. 角色 C — Manager / 对外宣传

### C1. 大盘首页（核心展示页）
- **头部卡片**：今日告警总数 / FIRING 数 / CRITICAL 数 / pipeline 成功率
- **趋势图**：告警数 per hour（折线）、执行吞吐 per minute（柱状）
- **饼图**：告警按 severity 分布、按 team 分布
- **热力/表格**：最活跃 pipeline Top N、最频繁告警 fingerprint Top N
- **接口**：`GET /stats/alerts`、`/stats/alerts/timeseries`、`/stats/executions` ← **全部缺（P0，看板的根）**

### C2. 对外宣传/Demo 页
- 精简只读大盘：去掉处置按钮，只留指标 + 趋势 + 一个 demo pipeline 实时触发动画
- 适合截图/录屏/投屏
- 可选「演示模式」：自动提交一个示例事件 → 实时展示告警产生全过程

### C3. Namespace 总览页（可选）
- 跨 namespace 汇总（若开放）← 与 ADR-0002 软隔离冲突，需单独决策（P3）

---

## 5. 页面 × 接口依赖矩阵

| 页面 | 已有接口 | 需补接口（见 API 审计 §4） |
|---|---|---|
| A1 Pipeline 列表 | `GET .../pipelines` | 真分页（P1） |
| A2 编辑器 | `/validate/*`、`POST .../versions` | — |
| A3 版本管理 | `GET .../versions`、publish/archive | diff（P2） |
| A4 Subscription 列表 | `GET .../subscriptions` | — |
| A5 Subscription 编辑 | `POST/PUT .../subscriptions` | — |
| B1 告警列表 | `GET /alerts` | severity+时间范围筛选、真分页（P0/P1） |
| B2 告警详情 | `GET /alerts/{id}`、evidence | ack/resolve 写接口（P1） |
| B3 执行历史 | `GET /executions` | status+时间筛选、真分页（P0/P1） |
| B4 失败执行 | `GET /failed-executions` | retry（P2） |
| **C1 大盘** | — | **聚合 + 时间序列（P0，全部缺）** |
| C2 Demo | `/events` | — |

---

## 6. 开发优先级建议

| 阶段 | 内容 | 前置 |
|---|---|---|
| **Sprint 0** | 前端脚手架 + 统一 API 客户端（信封） | 后端 B5 信封落地 |
| **Sprint 1** | C1 大盘（P0 聚合/时间序列）+ B1 告警列表 | 后端 P0 接口 |
| **Sprint 2** | A1/A2/A4/A5 配置 CRUD + A2 校验/干跑（已有接口，可先做） | 信封 |
| **Sprint 3** | B2 告警详情/证据 + B3/B4 执行历史 | 后端 P1 筛选/分页 + ack/resolve |
| **Sprint 4** | C2 Demo + A3 版本管理/diff + B4 重试 | P2 接口 |

> 最小可演示路径：**信封 → 大盘（C1）→ 告警列表（B1）**。这三个能跑通就能对外展示核心价值。

---

## 7. 待决策项

1. **统一信封**是否在 B5 先做（强烈建议 yes）。
2. **告警 ack/resolve** 是否纳入一期（依赖 ADR-0005 决策，见 design-review §3）。
3. **跨 namespace 汇总**（C3）是否开放（与 ADR-0002 冲突）。
4. **实时推送**是否需要（一期轮询够不够）。
5. 认证/权限模型（页面层 namespace 切换是否足够，还是要真 RBAC）。
