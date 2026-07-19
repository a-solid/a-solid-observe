# 前端页面 AI 生成提示词集（静态 HTML Demo）

**日期**：2026-07-19
**状态**：Review（待评审，v2 — 去视觉硬编码版）
**用途**：本文档按页面分段，每段末尾给出**可直接复制给 AI 的提示词**，用于生成**纯静态 HTML demo 页面**（不接入真实后端，单文件可直接浏览器打开）。
**配套文档**：
- `2026-07-19-frontend-page-plan.md`（页面规划原始版）
- `2026-07-19-controlplane-api-audit.md`（后端接口现状，本文档据此对齐字段/路径/筛选维度，但 demo 内全部用静态 mock 数据）

---

## 0. 本文档的边界（很重要）

**本文档只负责"描述每个页面要做什么、给谁用、关键交互意图是什么、字段/枚举必须对齐什么"。**
**视觉风格、配色、字体、组件库、动效实现方式，全部交给设计侧（专门的 UI agent / skill，如 `ui-ux-pro-max`）去发挥创作，本文档不规定。**

也就是说，每页的提示词里：
- ✅ 会写：页面目标、布局分区、信息层级、关键交互、视觉**期望关键词**（方向性引导，非实现）、必须照抄的**字段名/枚举/接口路径**。
- ❌ 不写：具体 hex 色值、具体组件库、玻璃拟态/深色背景等具体技法、动效的 CSS/SVG 实现细节。

### 0.1 全局视觉期望（方向词，不绑死）

- **明暗基调**：倾向**浅色为主**（不要全部深色）；是否提供暗色模式由设计侧决定。深色仅作为可选主题，不作为默认强制基调。
- **气质**：现代 SaaS / 数据产品感，专业、克制、有呼吸感；**不能像传统运维/监控后台**那样灰暗拥挤。要能拿去给客户 Manager 对外展示。
- **贯穿隐喻**：数据像流体在"管道"中流动 —— 一条溯源链路 `Source → Subscription → Pipeline → Alert`。各页围绕这条链路做不同形态的可视化（这是**功能/交互层面的核心**，具体长什么样由设计发挥）。
- **状态/严重度必须一眼可辨**：severity（CRITICAL/WARNING/INFO）、各类 status、执行结果（SUCCESS/SHORT_CIRCUITED/FAILED）要有清晰的视觉区分（颜色 + 图标/形状，不只靠颜色）。具体配色由设计侧定。
- **实时感**：关键页面要有"系统正在运转"的活力（数字变化、新数据进入、流动指示），但动效要克制、专业，不浮夸。
- **可访问性**：对比度、键盘可达、不只用颜色传达信息、尊重 `prefers-reduced-motion`（交给设计侧落实，提示词里作为期望提出）。

### 0.2 技术约束（仅这一项是硬性）

- **纯静态 HTML**：每个页面是一个**自包含的 `.html` 文件**，可直接双击在浏览器打开。
- **不依赖构建工具/不依赖 npm**：所需 CSS/JS 库走 CDN。
- **mock 数据**：写在 `<script>` 里的 JS 对象/数组，**字段名严格照抄各页"接口字段对齐"**。
- **不造接口**：demo 里不真的发请求；如需体现接口路径，在代码注释里标注，例如 `// GET /api/v1/alerts?namespace=ops&severity=CRITICAL`。
- **响应式**：至少桌面端完美，平板/窄屏不崩。

> 具体用 Tailwind 还是手写 CSS、用 ECharts 还是别的图表库、动效用 GSAP 还是纯 CSS —— **由设计侧自行决定**，提示词不限定。

---

## 1. 页面清单与贯穿主线

三个角色分组，共 11 个页面 + 1 个全局骨架：

| 分组 | 页面 | 一句话 |
|---|---|---|
| **C 对外展示** | C1 大盘首页 / C2 演示页 | 给 Manager 看整体价值，招牌展示 |
| **B 告警处理** | B1 告警列表 / B2 告警详情 / B3 执行历史 / B4 失败执行 | 值班排障 |
| **A 配置** | A1 Pipeline 列表 / A2 编辑器+干跑 / A3 版本管理 / A4 Subscription 列表 / A5 Subscription 编辑 | 工程配置 |
| **骨架** | 全局导航 | 串联所有页面 |

**贯穿主线 — 溯源链路**：`Source → Subscription → Pipeline → Alert`
- 因为 95% 的 pipeline 只有一个节点，所以"管道"的重点不是 pipeline 内部多节点，而是**一条告警/事件从哪来、经过谁、被谁判定、产出什么**。
- 这条链路在不同页面以不同形态出现（详见各页）。

---

## 2. C 类 — Manager / 对外展示页

### 2.1 C1 大盘首页

**页面目标**：对外展示核心价值的英雄页。一眼看清"今天系统监测了多少、抓到多少问题、成功率多高"。面向 Manager，截图录屏首选。

**接口字段对齐（demo 内 mock，字段照抄）**：
- `GET /api/v1/stats/alerts?namespace=&from=&to=` → `{ bySeverity: {CRITICAL,WARNING,INFO}, byStatus: {FIRING,RESOLVED}, total }`
- `GET /api/v1/stats/alerts/timeseries?namespace=&from=&to=&bucket=1h` → `[{ bucketStart, count }]`
- `GET /api/v1/stats/executions?namespace=&from=&to=` → `{ success, failed, short_circuited, successRate }`
- hero 计数：今日告警总数、FIRING 数、CRITICAL 数、pipeline 成功率。

**布局结构（信息层级，非视觉）**：
1. **顶部 hero 区**：一条溯源链路可视化（Source → Subscription → Pipeline → Alert），叠加"今日 N 个事件流经、M 个触发告警"的实时计数。
2. **4 张 KPI 卡片**：今日告警总数 / FIRING / CRITICAL / 执行成功率。
3. **趋势区**：告警趋势（按小时，按 severity 堆叠）+ 执行吞吐（按小时，success/failed）。
4. **分布区**：告警按 severity 分布、按 team 分布。
5. **Top N**：最活跃 pipeline Top 5、最频繁告警 fingerprint Top 5。

**交互/视觉期望关键词**：
- KPI 数字有"进入"动效（count-up 之类），但克制。
- 趋势图能体现"实时"（新数据点会进入），但不必真连后端，用定时器模拟即可。
- hero 链路要有"流动感"，体现系统在运转。
- **整体要像现代数据产品的大盘，不像 Grafana 那种纯运维看板。**

**📋 复制这段给 AI：**

```
你是一位资深前端工程师 + 产品设计师。请生成一个"可观测性平台大盘首页"的纯静态 HTML demo，用于给客户 Manager 对外展示。

【硬性技术约束】
- 单个自包含 .html 文件，可直接浏览器打开。
- 所需 CSS/JS 库走 CDN，不依赖构建工具、不依赖 npm。
- 不发真实请求；数据写在 <script> 的 mock 数组里，字段名严格照抄下方接口约定；代码注释标注接口路径。
- 桌面端完美，窄屏不崩。

【设计自由度 —— 请你发挥】
配色、字体、组件库、图表库、明暗模式、动效实现方式，全部由你决定。唯一的方向性要求：
- 倾向浅色为主的现代 SaaS / 数据产品气质，专业、有呼吸感，不要全部深色，不要像传统运维监控后台。
- 各类状态/严重度（severity、status、执行结果）必须一眼可辨，且不只靠颜色（配图标/形状）。
- 尊重 prefers-reduced-motion。
你可以在生成前先用你的设计判断力选定一套设计语言，然后贯穿整页。

【页面要做什么】（按信息层级）
1. 顶部 hero 区：可视化一条溯源链路 Source → Subscription → Pipeline → Alert（具体形态你定，要有"流动/运转"的活力感），上方叠加文字「今日 1,284 个事件流经 · 触发 47 条告警」，数字有进入动效。
2. 4 张 KPI 卡片：今日告警总数 64 / FIRING 8 / CRITICAL 12 / 执行成功率 98.4%。FIRING、CRITICAL 卡片要在视觉上比普通卡片更"值得关注"。每张卡片配一个趋势小图。
3. 趋势区（两块）：告警趋势（按小时，CRITICAL/WARNING/INFO 三条堆叠）+ 执行吞吐（按小时，success/failed 双系列）。趋势要能体现"实时"——用定时器定时往数据里加一个新点，让图自然进入新数据。
4. 分布区（两块）：告警按 severity 分布 + 告警按 team 分布。
5. 底部 Top5：最活跃 pipeline、最频繁告警 fingerprint。

【必须照抄的字段/接口】（mock 用）
- // GET /api/v1/stats/alerts?namespace=ops&from=...&to=... → { bySeverity:{CRITICAL,WARNING,INFO}, byStatus:{FIRING,RESOLVED}, total }
- // GET /api/v1/stats/alerts/timeseries?namespace=ops&from=...&to=...&bucket=1h → [{ bucketStart, count }]
- // GET /api/v1/stats/executions?namespace=ops&from=...&to=... → { success, failed, short_circuited, successRate }

请直接输出完整 HTML。重点是：让人一看觉得"这是一个专业的、值得对外展示的数据产品大盘"。
```

---

### 2.2 C2 对外 Demo / 演示页（招牌）

**页面目标**：全产品的"招牌动作"。可视化一个事件如何从 Source 注入，经过 Subscription 过滤、Pipeline 判定，最终产出一条 Alert。适合投屏/录屏/截图。提供"演示模式"按钮自动播放。

**接口字段对齐**：
- `POST /api/v1/events`（提交事件，返回 eventId）— demo 里假装提交。
- `GET /api/v1/alerts/{id}`（告警详情：severity/labels/annotations/startsAt）— 末端产出的告警卡字段照抄。

**布局结构**：
1. **居中舞台**：溯源链路占主区域，4 段 Source → Subscription → Pipeline → Alert。
2. **播放控制**：「▶ 演示模式」按钮 + 自动循环开关。
3. **每段可点击**：点击节点展开该实体详情（source 配置 / subscription 条件 / pipeline 定义 / alert 元信息）。
4. **末端产出 Alert**：流程跑完后，末端产出一张告警卡。

**交互/视觉期望关键词**：
- 完整播放一次"事件旅程"：注入 → 流动 → 在 Subscription 被过滤（部分事件被剔除）→ Pipeline 判定 → 末端产出告警。
- 流程要讲得清"为什么这条事件变成了告警"——这是对外展示的核心叙事。
- 节点点击展开详情，交互流畅。
- **要有 wow 感，但又不能浮夸到像玩具。**

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 动效/交互设计师。生成一个"可观测性平台对外演示页"，这是产品的招牌 demo，目标是让客户一看就理解"我们怎么把一个事件变成一条精准告警"。纯静态 HTML，单文件，浏览器直接打开。

【硬性技术约束】
- 单 .html 文件，CDN 引入库，不依赖构建工具。
- 全部 mock 数据写在 script 里，不发真实请求，代码注释标接口路径。

【设计自由度 —— 请你发挥】
配色/字体/库/动效技法/明暗全部你定。方向要求：
- 倾向浅色为主、现代专业，不要全部深色，不要花哨到像玩具。
- 这页的动效是灵魂：要让"事件在管道中流动、被过滤、被判定、产出告警"这个过程清晰、流畅、有叙事感。
- 尊重 prefers-reduced-motion（提供静态降级）。

【页面要做什么】
中央舞台展示溯源链路 4 个节点：
  ① Source（标「CDC 事件」）
  ② Subscription（标「orders 表 · 金额>10000」）
  ③ Pipeline（标「check: 高额订单告警」）
  ④ Alert（末端，播放后产出）
节点之间有连接。底部「▶ 演示模式」按钮 + 「自动循环」开关。

【播放流程】（点演示模式触发，约 3 秒，可循环）
1. 一个事件从 Source 注入。
2. 流向 Subscription；到达时，额外 2-3 个"不满足条件"的事件被剔除（视觉上弹开/淡出），主事件通过。
3. 流向 Pipeline，节点表示"正在判定"。
4. 到达末端，产出一张告警卡：severity 徽标（CRITICAL）、fingerprint、labels.entity「orders」、annotations「金额 ¥58,200 超阈值」、startsAt 时间。
5. 全程节点高亮跟随事件位置。

【交互】
每个节点可 hover、可点击 → 展开该节点详情（source 配置 / subscription 条件树 / pipeline 摘要 / alert 元信息），用 mock 数据。

【必须照抄的字段/接口】
- alert 卡片字段：severity / fingerprint / labels.entity / annotations / startsAt
- // POST /api/v1/events → eventId
- // GET /api/v1/alerts/{id}

请输出完整 HTML。重点是：动画讲清楚"事件→告警"的因果叙事，流畅、专业、有冲击力但不浮夸。
```

---

## 3. B 类 — 告警查看处理页

### 3.1 B1 告警列表页（值班首屏）

**页面目标**：值班/运维一进来就要看见"现在有什么火在烧"。最严重的问题最显眼、置顶，新告警能被注意到。

**接口字段对齐**：
- `GET /api/v1/alerts?namespace=&status=&team=&pipeline_id=&severity=&from=&to=&limit=` → 列表。
- 字段：`severity`（CRITICAL/WARNING/INFO）、`status`（FIRING/RESOLVED）、`fingerprint`、`labels.entity`、`startsAt`、`lastSeenAt`、`dedupCount`、`team`、`pipelineId`。

**布局结构**：
1. **筛选条**：severity（多选）、status、team、pipeline、时间范围。
2. **severity 分组概览**：按 severity 的计数（CRITICAL 12 / WARNING 47 / INFO 5），可点击筛选。
3. **主区告警列表**：每条告警一个条目，CRITICAL 永远置顶，RESOLVED 视觉弱化。
4. **条目内容**：severity 标识、fingerprint、labels.entity、相对时间、dedupCount、team、所属 pipeline、点击进入详情。

**交互/视觉期望关键词**：
- **最严重的告警要"抢眼"**（FIRING+CRITICAL），让值班一眼锁定，但不刺眼到影响阅读。
- 新告警进入时有"进入"提示（暗示实时），但克制。
- 筛选条要好用、好看，不是原生控件堆砌。
- **不要做成密密麻麻的运维日志表格**，要有产品感。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成「可观测性平台告警列表页」纯静态 HTML demo（单文件）。这是值班人员首屏，核心诉求：让人一眼看到"现在最严重的问题是什么"。

【硬性技术约束】
- 单 .html 文件，CDN 库，不依赖构建。mock 数据写 script，不发请求，注释标接口。桌面端完美。

【设计自由度 —— 请你发挥】
配色/字体/库/明暗/动效技法你定。方向要求：
- 倾向浅色为主、现代专业，不要全部深色，不要像运维日志后台。
- severity（CRITICAL/WARNING/INFO）必须一眼可辨，且不只靠颜色。
- FIRING+CRITICAL 的告警要"抢眼"（让值班注意），但不能刺眼到干扰阅读其余信息。
- 尊重 prefers-reduced-motion。

【页面要做什么】
1. 顶部导航 + 筛选条：severity 多选（CRITICAL/WARNING/INFO）、status（FIRING/RESOLVED/全部）、team、pipeline、时间范围（最近1h/24h/7d）。筛选控件要好看，别用原生 select 直接堆。
2. severity 分组概览：三个可点击的统计块 CRITICAL 12 / WARNING 47 / INFO 5，点击筛选主区。
3. 主区告警列表（卡片或富列表，不要死板表格）：每条含 severity 标识、fingerprint、labels.entity（如 orders-db）、startsAt（显示"3 分钟前"）、dedupCount（×5）、team、所属 pipeline 名。
4. mock 8-12 条；CRITICAL+FIRING 排最前；RESOLVED 的条目视觉弱化。
5. 顶部一个"实时/已连接"状态的指示。

【交互期望】
- 条目点击 → 进入详情（demo 里 console.log 或占位即可）。
- 页面加载几秒后，用定时器在列表顶部"进入"一条新 CRITICAL 告警，制造实时感（尊重 reduced-motion 时关闭）。

【必须照抄的字段/接口】
- 字段：severity / status / fingerprint / labels.entity / startsAt / lastSeenAt / dedupCount / team / pipelineId
- // GET /api/v1/alerts?namespace=ops&severity=CRITICAL&status=FIRING&from=...&to=...

请输出完整 HTML。
```

---

### 3.2 B2 告警详情页（排障核心 + 溯源链路复用）

**页面目标**：点进一条告警，顶部直接展示它"从哪来"的溯源链路，中间看证据，底部看状态时间线，并提供处置动作。

**接口字段对齐**：
- `GET /api/v1/alerts/{id}?namespace=` → 元信息（severity/labels/annotations/startsAt/lastSeenAt/endsAt/resolvedAt/status/dedupCount）。
- `GET /api/v1/alerts/{id}/evidence?namespace=` → 证据（节点输出 JSON/表格）。
- 关联 execution：跳到 `GET /api/v1/executions/{id}`。
- 写操作（demo 里按钮可见但 mock）：`POST /alerts/{id}/ack`、`POST /alerts/{id}/resolve`。

**布局结构**：
1. **顶部溯源链路条**：静态展示这条告警的实际溯源 `Source → Subscription → Pipeline → Alert`，每个节点填真实溯源值（如 Pipeline: 「高额订单告警 v3」），可点击跳转/展开该实体。
2. **告警元信息卡**：severity、fingerprint、labels、annotations、时间线（starts → lastSeen → ends/resolved）。
3. **证据区**：`evidence` 节点输出，提供 JSON 视图 + 表格视图切换。
4. **状态流转时间线**：FIRING → (ack) → RESOLVED 的 dedup 演化。
5. **处置动作条**：ack / resolve / silence 按钮（mock，点击给反馈）。

**交互/视觉期望关键词**：
- 顶部溯源链路是这页的视觉重点，要让读者秒懂"这条告警是哪个源、哪个订阅、哪个 pipeline 产出的"。
- 证据 JSON 要可读（语法高亮 + 可切表格）。
- 时间线讲清"这条告警的生命周期"。
- 处置动作（尤其对 CRITICAL 的 Resolve）要有恰当的强调和确认感。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成「告警详情页」纯静态 HTML demo（单文件）。这是排障核心页，顶部要用溯源链路清楚展示这条告警"从哪来"。

【硬性技术约束】
- 单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度 —— 请你发挥】
配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业，不要全部深色；severity 一眼可辨；尊重 reduced-motion。

【页面要做什么】（上到下）
1. 顶部溯源链路条（横向，4 节点）：Source「CDC: orders 表 INSERT」→ Subscription「高额订单监控」→ Pipeline「check 高额订单告警 v3」→ Alert「本告警」。每个节点带该实体的图标和名称，可点击。页面加载时链路有一次"点亮/流转"的进入感（reduced-motion 下静态）。
2. 告警元信息卡：severity 徽标（本条 CRITICAL，要醒目但不刺眼）、fingerprint、labels（entity=orders, team=payment, severity=CRITICAL）、annotations（描述）。配一个时间线小图：startsAt → lastSeenAt → endsAt/resolvedAt，已发生亮、未来灰。
3. 证据区：标题「Pipeline 节点输出（evidence）」。提供 JSON 视图（语法高亮，mock 一段 check 输出：{matched:true, value:58200, threshold:10000}）和表格视图，可 tab 切换。
4. 状态流转时间线（垂直）：14:28:01 FIRING（首次）→ 14:28:15 dedup ×3 → 14:30:00 ack by alice → 14:35:22 RESOLVED。每个节点带时间、动作、操作人。
5. 底部固定操作条：按钮「Acknowledge」「Resolve」「Silence 1h」。Resolve 是关键动作，要有恰当强调；点击任一按钮给一个反馈（toast 等，mock）。

【必须照抄的字段/接口】
- 字段：severity / labels / annotations / startsAt / lastSeenAt / endsAt / resolvedAt / status / dedupCount / fingerprint；evidence 为节点输出对象
- // GET /api/v1/alerts/{id}?namespace=ops
- // GET /api/v1/alerts/{id}/evidence?namespace=ops

请输出完整 HTML，溯源链路是这页的视觉重点。
```

---

### 3.3 B3 执行历史页

**页面目标**：看一段时间内 pipeline 跑了多少次、结果如何（成功/短路）、每次耗时。体现"执行流水"感，比纯表格更易读。

**接口字段对齐**：
- `GET /api/v1/executions?namespace=&pipeline_id=&status=&from=&to=&limit=` → `triggerType`、`triggerEvent` 摘要、`status`（SUCCESS/SHORT_CIRCUITED）、`durationMs`、`startedAt`。

**布局结构**：
1. **筛选 + 统计概览**：pipeline 选择、status 多选、时间范围；概览胶囊：SUCCESS 数、SHORT_CIRCUITED 数、平均耗时。
2. **执行列表（时间轴/泳道感）**：每条执行一行，按时间倒序：
   - 时间戳 + triggerType（CDC/CRON/API）。
   - 耗时可视化（宽度 ∝ durationMs，颜色 = 结果）。
   - 结果徽标 + 耗时。
3. **展开行**：点击某行展开 triggerEvent 摘要。

**交互/视觉期望关键词**：
- 要有"流水"感（执行在时间上依次发生），不是静态报表。
- 结果（SUCCESS/SHORT_CIRCUITED）一眼可辨。
- 新执行进入时有进入感（暗示实时），克制。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成「pipeline 执行历史页」纯静态 HTML demo（单文件）。要体现"执行流水"感，不要做成死板表格。

【硬性技术约束】
- 单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度 —— 请你发挥】
配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业，不要全部深色；执行结果（SUCCESS/SHORT_CIRCUITED）一眼可辨且不只靠颜色；尊重 reduced-motion。

【页面要做什么】
1. 顶部导航 + 筛选条：pipeline 下拉、status 多选（SUCCESS/SHORT_CIRCUITED）、时间范围。右侧统计概览：SUCCESS 1,204 / SHORT_CIRCUITED 87 / 平均耗时 320ms。
2. 主区执行列表（每条一行，mock 10-15 条，按时间倒序），每行含：
   - 时间戳（14:32:01）+ triggerType 标识（CDC/CRON/API，用图标区分）。
   - 耗时可视化：宽度按 durationMs 比例（最长那条占满），按结果着色，标注「320ms」。
   - 结果徽标 + 时长。SHORT_CIRCUITED 要能和 SUCCESS 区分（如闪电图标）。
   - 可点击展开。
3. 点击某行 → 下方展开 triggerEvent 摘要（mock JSON：触发事件关键字段）。
4. 顶部"实时/已连接"指示。

【交互期望】
- 行有进入动效（stagger）。
- 加载几秒后用定时器在顶部"进入"一条新执行行（reduced-motion 下关闭）。

【必须照抄的字段/接口】
- 字段：triggerType / triggerEvent / status(SUCCESS,SHORT_CIRCUITED) / durationMs / startedAt / pipelineId
- // GET /api/v1/executions?namespace=ops&status=SUCCESS&from=...&to=...

请输出完整 HTML。
```

---

### 3.4 B4 失败执行页（定位病灶）

**页面目标**：定位失败"病灶"。因为 95% pipeline 是单节点，失败就是这一个节点的问题，要可视化"在哪一步崩的"。

**接口字段对齐**：
- `GET /api/v1/failed-executions?namespace=&pipeline_id=&limit=` → `errorType`、`nodeName`、`errorMessage`、`stackTrace`、`status`（PENDING/RESOLVED/IGNORED）、`createdAt`。
- 重试（demo 按钮可见 mock）：`POST /failed-executions/{id}/retry`。

**布局结构**：
1. **左侧失败列表**：errorType 分组 + 失败卡片（errorType、nodeName、pipeline、status、createdAt）。
2. **右侧诊断面板**（选中某条后）：可视化"输入 → 节点处理 → 输出"，标注失败发生在哪一步（根据 errorType）。
3. **错误详情**：errorMessage + 可展开 stackTrace + 「一键重试」按钮。

**交互/视觉期望关键词**：
- 诊断面板要让人秒懂"这个节点在哪一步、因为什么崩了"。
- 可以用"信号通道/波形/流程崩点"之类的隐喻，但**具体形态由设计发挥**，不强制要求"医疗监护仪"那种特定风格。
- PENDING（未处理）的失败要能和 RESOLVED/IGNORED 区分，吸引处理。
- 选中不同失败时，诊断面板重新展示一次该失败的"过程"。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成「失败执行诊断页」纯静态 HTML demo（单文件）。这页的目标：帮运维快速定位"一个 pipeline 执行在哪一步、因为什么崩了"。注意：本项目 95% 的 pipeline 只有一个节点，所以失败基本就是单节点的问题。

【硬性技术约束】
- 单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度 —— 请你发挥】
配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业，不要全部深色；失败/状态要一眼可辨且不只靠颜色；尊重 reduced-motion。
诊断面板的可视化形态由你决定（可以是信号通道、流程崩点、时间轴、示波图等任意你判断最有效的形式），目标是让读者秒懂"在哪一步崩了"。不必非要做成"医疗监护仪"。

【页面要做什么】（左右两栏）
左栏（失败列表）：
- errorType 分组筛选（NPE 3 / Timeout 2 / EmitError 1 ...）。
- 失败卡片列表（mock 5-6 条），每张：errorType 标识、nodeName（如 check-amount）、pipeline 名、status（PENDING/RESOLVED/IGNORED）、createdAt 相对时间。PENDING 要能吸引处理（比 RESOLVED/IGNORED 更醒目）。选中态高亮。

右栏（诊断面板，选中某条后）：
- 标题「节点诊断 · {nodeName}」+ errorType + 「一键重试」按钮（点击给反馈，mock）。
- 核心诊断可视化：展示「输入 → 节点处理 → 输出」，并清晰标注失败发生在哪一步（位置根据 errorType，如 NPE 标在 check 求值段、EmitError 标在输出段），配标签如「NPE @ check」。
- 选中不同失败卡片时，诊断面板重新展示一次该失败的过程（有进入感，reduced-motion 下静态）。
- 诊断面板下方：errorMessage（突出显示）+ 可展开的 stackTrace。

【必须照抄的字段/接口】
- 字段：errorType / nodeName / errorMessage / stackTrace / status(PENDING,RESOLVED,IGNORED) / createdAt / pipelineId
- // GET /api/v1/failed-executions?namespace=ops
- // POST /api/v1/failed-executions/{id}/retry

请输出完整 HTML，诊断面板是这页的灵魂——要让人一眼看懂"崩在哪、为什么"。
```

---

## 4. A 类 — Pipeline / Subscription 配置页

### 4.1 A1 Pipeline 列表页

**页面目标**：维护者看自己有哪些 pipeline、状态如何。要有产品感，不像后台 CRUD。

**接口字段对齐**：
- `GET /api/v1/namespaces/{ns}/pipelines` → `name`、`team`、`application`、`status`（DRAFT/PUBLISHED/ARCHIVED）、`currentVersion`、`updatedAt`、`labels`。

**布局结构**：
1. 标题 + 「+ 新建 Pipeline」。
2. 筛选条：status、team、application、搜索。
3. **卡片网格**：每张卡片 = 一个 pipeline，含 name、描述、team/application、状态、currentVersion、updatedAt、近期执行小趋势。
4. 卡片操作：查看 / 编辑 / 归档（hover 或菜单）。

**交互/视觉期望关键词**：
- 状态（PUBLISHED/DRAFT/ARCHIVED）一眼可辨（如不同状态指示），但不只用颜色。
- 卡片有产品感，不像 CRUD 表格行。
- hover 有恰当反馈。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成「Pipeline 列表页」纯静态 HTML demo（单文件）。用卡片网格，要有产品感，不像后台 CRUD。

【硬性技术约束】
- 单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度 —— 请你发挥】
配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业，不要全部深色；状态（DRAFT/PUBLISHED/ARCHIVED）一眼可辨且不只靠颜色；尊重 reduced-motion。

【页面要做什么】
1. 顶部导航 + 标题「Pipelines」+ 右侧「+ 新建 Pipeline」主按钮。
2. 筛选条：status 多选（DRAFT/PUBLISHED/ARCHIVED）、team、application、搜索框。
3. 卡片网格（响应式），mock 9 张，每张含：name（粗）+ 描述一行、状态指示（PUBLISHED/DRAFT/ARCHIVED）、team/application 标签、currentVersion「v3」、updatedAt「2 小时前」、近期执行小趋势图。
4. 卡片操作：查看 / 编辑 / 归档（hover 或菜单出现）。
5. 状态分布有差异：大部分 PUBLISHED，2 张 DRAFT，1 张 ARCHIVED（视觉弱化）。

【必须照抄的字段/接口】
- 字段：name / team / application / status(DRAFT,PUBLISHED,ARCHIVED) / currentVersion / updatedAt / labels
- // GET /api/v1/namespaces/{ns}/pipelines

请输出完整 HTML。
```

---

### 4.2 A2 Pipeline 编辑器 + 干跑面板

**页面目标**：左侧编辑 pipeline（JSON / 可视化），右侧校验 + 干跑结果。即使单节点也要好用、好懂。

**接口字段对齐**：
- `POST /api/v1/validate/pipeline` → 校验错误列表。
- `POST /api/v1/validate/dry-run` → outcome + 每节点输出 + 触发的告警。
- 版本：`POST .../versions`、`POST .../versions/{v}/publish`。

**布局结构**：
1. **顶部工具条**：pipeline 名 + 版本号 + 「校验」「干跑」「保存版本」「发布」。
2. **左栏（编辑）**：tab 切换「可视化」「JSON」。可视化：单节点 pipeline 就一个节点卡片（check/emit/script），可配字段。
3. **右栏（校验 + 干跑）**：
   - 校验结果（通过 / 错误列表）。
   - 干跑：选示例事件 → 展示输入 → 节点处理 → 输出 → 是否触发告警。
4. **底部**：版本操作。

**交互/视觉期望关键词**：
- 干跑结果要能讲清"这个事件进来，节点怎么处理，最后有没有触发告警、为什么"。
- 校验结果清晰（通过 vs 错误，错误指明位置）。
- 配置类页面也要保持产品感，不要像 IDE。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成「Pipeline 编辑器 + 干跑面板」纯静态 HTML demo（单文件）。左右分栏：左编辑，右校验+干跑。

【硬性技术约束】
- 单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度 —— 请你发挥】
配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业，不要全部深色；结果状态一眼可辨；尊重 reduced-motion。干跑结果的可视化形态你定，目标是讲清"输入→处理→输出→是否告警"。

【页面要做什么】
1. 顶部工具条：pipeline 名「高额订单告警」+ 版本「draft v4」+ 按钮「校验」「干跑」「保存版本」「发布」（发布是主操作）。
2. 左栏编辑区：tab「可视化」「JSON」。
   - 可视化：单节点 pipeline 显示一个节点卡片（类型 check），可配字段（如 threshold:10000, compare:GT），有输入入口、输出口。简化即可。
   - JSON：一段 pipeline JSON（语法高亮，mock）。
3. 右栏：
   - 校验结果卡：默认 ✓「校验通过」，或 mock 1 条错误（某字段缺失）。
   - 干跑卡：「▶ 干跑」按钮 + 示例事件选择器。点干跑后展示：输入事件 → 节点处理 → 输出；matched=true 则展示「触发告警 CRITICAL」+ 节点输出 JSON（{matched:true, value:58200, threshold:10000}）；matched=false 则「未触发」。
4. 底部：版本操作「保存为新版本」「发布 v4」「查看历史」+ 最近版本时间线缩略。

【必须照抄的字段/接口】
- // POST /api/v1/validate/pipeline
- // POST /api/v1/validate/dry-run

请输出完整 HTML，干跑结果的可视化是这页的亮点（讲清因果）。
```

---

### 4.3 A3 Pipeline 版本管理页

**页面目标**：看一个 pipeline 的所有版本、发布历史、版本 diff、一键回滚。

**接口字段对齐**：
- `GET .../pipelines/{name}/versions` → 版本号、status、definitionHash、publishedBy、时间。
- diff（demo mock）：`GET .../versions/{v1}/diff/{v2}`。
- 发布/归档：`POST .../versions/{v}/publish`、`POST .../versions/{v}/archive`。

**布局结构**：
1. 左：版本时间线（每版一个节点，标 status、publishedBy、时间，当前发布版高亮）。
2. 右：选两版后的 **diff 视图**（左旧右新，增删行高亮）+ 「回滚到此版」按钮。

**交互/视觉期望关键词**：版本时间线清晰；diff 增删一目了然；回滚作为关键操作有确认感。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成「Pipeline 版本管理页」纯静态 HTML demo（单文件）。

【硬性技术约束】单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度】配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业；版本状态一眼可辨；尊重 reduced-motion。

【页面要做什么】（左右）
左栏（版本时间线）：
- 垂直时间线，mock 5 版（v1-v5）。每版：版本号、status（PUBLISHED/ARCHIVED/DRAFT）、publishedBy、时间。当前发布版高亮标记「current」。可勾选两版做 diff。
右栏：
- diff 标题「v3 → v4 变更」。
- 双栏 diff：左 v3、右 v4，行对齐，删除行/新增行高亮，mock 几处变更（如 threshold 10000→20000）。
- 底部按钮「回滚到 v3」「发布 v4」「归档 v3」。回滚是关键/有风险操作，要有恰当强调和确认感。

【必须照抄的字段/接口】
- 字段：version / status / definitionHash / publishedBy / 时间
- // GET /api/v1/namespaces/{ns}/pipelines/{name}/versions
- // GET .../versions/{v1}/diff/{v2}

请输出完整 HTML。
```

---

### 4.4 A4 Subscription 列表页

**页面目标**：看有哪些订阅、什么源、绑了哪些 pipeline。**重点体现 `pipelineIds:[a,b,c]`：一个订阅可绑多个 pipeline。**

**接口字段对齐**：
- `GET /api/v1/namespaces/{ns}/subscriptions` → `name`、`sourceType`（CDC/CRON/API）、`pipelineIds:[]`、`actionType`（RUN/SCHEDULE/CANCEL）、`cronExpression`、`status`。

**布局结构**：
1. sourceType 分组（CDC/CRON/API）。
2. **卡片网格**：每张订阅卡，含 name、sourceType、源配置摘要（CRON 显示 cron 表达式）、**绑定的 pipeline 数组**（清晰展示 pipelineIds，多个时显示 +N）、actionType、status。

**交互/视觉期望关键词**：
- sourceType（CDC/CRON/API）一眼可辨（图标）。
- **必须清楚展示一个订阅绑了多个 pipeline**（pipelineIds 数组）—— 这是这页的核心信息。
- 卡片产品感，hover 反馈。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成「Subscription 列表页」纯静态 HTML demo（单文件）。卡片网格。重点：一个 subscription 可绑定多个 pipeline（pipelineIds 数组），要非常清楚地展示出来。

【硬性技术约束】单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度】配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业；sourceType 一眼可辨；尊重 reduced-motion。

【页面要做什么】
1. 顶部导航 + 标题「Subscriptions」+「+ 新建」按钮。
2. sourceType 分组：CDC / CRON / API（带数量）。
3. 卡片网格（响应式），mock 8 张，每张含：
   - sourceType 标识（CDC/CRON/API，用图标区分）+ name。
   - 源配置摘要：CDC 显示「mq+topic+table+opTypes」；CRON 显示 cron 表达式（如 */5 * * * *）；API 显示 apiName。
   - 【重点】「绑定 Pipeline」区：清晰展示 pipelineIds 数组，单个就一个胶囊，多个（2-3 个）要清楚展示全部或带 +N。务必有几张绑 2-3 个 pipeline 的卡片。
   - actionType（RUN/SCHEDULE/CANCEL）+ status。
4. 卡片 hover 反馈。

【必须照抄的字段/接口】
- 字段：name / sourceType(CDC,CRON,API) / pipelineIds(数组) / actionType(RUN,SCHEDULE,CANCEL) / cronExpression / status
- // GET /api/v1/namespaces/{ns}/subscriptions

请输出完整 HTML，务必体现「一个 subscription 绑多个 pipeline」。
```

---

### 4.5 A5 Subscription 编辑页（条件树 + 分叉链路预览）

**页面目标**：配置一个订阅：选 pipeline（可多个）→ 配源 → 条件编辑器（Condition 树）→ actionType。右侧实时预览这条订阅的**分叉链路**：Source → Subscription → 多个 Pipeline。

**接口字段对齐**：
- `POST/PUT /api/v1/namespaces/{ns}/subscriptions` → `pipelineIds:[]`、source 配置（CDC: mq/topic/db/table/opTypes；CRON: cronExpression/concurrent；API: apiName）、`fieldFilter`（Condition 树：AND/OR/Compare/In）、`actionType`。

**布局结构**：
1. 左栏表单：
   - **Pipeline 绑定**：多选器，支持 `pipelineIds:[a,b,c]`。
   - **源配置**：sourceType 切换 → 对应字段。
   - **条件编辑器**：`Condition` sealed 树可视化（AND/OR 容器 + Compare/In 叶子），可增删节点。这页重点。
   - **actionType** 选择。
2. 右栏预览：**分叉链路**：
   ```
   Source ──▶ Subscription ──┬─▶ Pipeline A ──▶ (告警)
                             ├─▶ Pipeline B ──▶ (告警)
                             └─▶ Pipeline C ──▶ (告警)
   ```
   实时反映左侧 pipelineIds 数量；source/subscription 节点可点击展开配置。

**交互/视觉期望关键词**：
- 右侧分叉链路是这页的视觉重点，要让人秒懂"这个订阅把一个源的事件分发给哪几个 pipeline"。
- 加/减一个 pipeline，右侧分叉实时变化。
- 条件树编辑器要好用、直观（AND/OR/Compare/In 节点可增删）。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师 + 交互设计师。生成「Subscription 编辑页」纯静态 HTML demo（单文件）。左右分栏：左表单，右实时分叉链路预览。这是配置页里最有看点的一页。

【硬性技术约束】单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度 —— 请你发挥】
配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业，不要全部深色；尊重 reduced-motion。
右侧分叉链路预览是这页灵魂，可视化形态你定（流程图、树、管道等均可），目标是让人秒懂"一个源的事件分发给哪几个 pipeline"。加/减 pipeline 时分叉要实时变化。

【页面要做什么】

左栏（表单）：
1. Pipeline 绑定区（重点）：多选器，mock 已选 [高额订单告警] [风控告警] [+ 添加]。支持一个订阅绑多个 pipeline（pipelineIds 数组），可加可删。
2. 源配置区：sourceType tab（CDC/CRON/API），选中后显示对应字段表单（mock CDC：mq、topic、db、table、opTypes 多选）。
3. 条件编辑器（重点）：可视化 Condition 树。顶部 AND/OR 容器节点，下挂叶子（Compare：字段 op 值，如 amount GT 10000；In：字段 in [v1,v2]）。节点可增删。mock 一棵两层树（AND 下两个 Compare 叶子）。
4. actionType 选择：RUN/SCHEDULE/CANCEL。

右栏（分叉链路预览）：
- 一个「配置预览」区，展示分叉链路：
  - Source 节点（按选的 sourceType 显示图标 + 摘要）。
  - 连到 Subscription 节点（显示条件摘要「amount > 10000」）。
  - Subscription 向右分叉到多个 Pipeline 节点（数量 = 左侧 pipelineIds，mock 3 条：高额订单告警 / 风控告警 / 库存告警），每个 Pipeline 末端一个告警产出标识。
- 左侧「+ 添加 pipeline」时，右侧分叉实时多出一条分支（有进入感）—— 用一个「+ 添加演示分支」按钮模拟这个交互。
- 每个节点可 hover、可点击展开配置摘要。

【必须照抄的字段/接口】
- 字段：pipelineIds(数组) / sourceType(CDC,CRON,API) / 源字段 / fieldFilter(Condition 树) / actionType(RUN,SCHEDULE,CANCEL)
- // POST /api/v1/namespaces/{ns}/subscriptions

请输出完整 HTML。分叉链路预览是这页的灵魂，要流畅、清楚体现「一绑多」。
```

---

## 5. 全局骨架 / 导航

**页面目标**：把以上页面串成一个完整 demo，顶部统一 namespace 切换 + 角色页签，在页面间跳转。

**接口字段对齐**：`GET /api/v1/namespaces` → namespace 列表（mock：ops / payment / risk）。

**布局结构**：
- 顶部导航：左 logo、中 namespace 切换器（全局生效）、右 角色页签（配置 / 告警 / 大盘）。
- 角色页签决定次级导航分组：
  - 大盘：C1 / C2
  - 告警：B1 / B2 / B3 / B4
  - 配置：A1 / A2 / A3 / A4 / A5
- 页面切换有过渡。

**交互/视觉期望关键词**：namespace 切换有全局反馈感；角色页签切换流畅；导航分组清晰；当前所在位置明确。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 产品设计师。生成一个「全局导航骨架」纯静态 HTML demo（单文件），用于把多个页面串成一个完整可演示的 demo。

【硬性技术约束】单 .html 文件，CDN 库，不依赖构建。mock 写 script，不发请求，注释标接口。桌面端完美。

【设计自由度】配色/字体/库/明暗/动效你定。方向：浅色为主、现代专业；当前导航位置明确；尊重 reduced-motion。

【页面要做什么】
1. 顶部固定导航条：
   - 左：logo「A-Solid Observe」。
   - 中：namespace 切换器（下拉，mock ops / payment / risk，当前 ops）。切换时有一次全局反馈（如顶部一道流光扫过）。
   - 右：角色页签三组（大盘 / 告警 / 配置），当前激活页签明确标识。
2. 角色页签决定下方次级导航：
   - 大盘 → C1 首页 / C2 演示
   - 告警 → B1 列表 / B3 执行历史 / B4 失败执行
   - 配置 → A1 Pipeline / A4 Subscription
3. 主内容区：占位卡片，标题写当前选中页（如「大盘 · 首页」），内容写「（此处嵌入对应页面 demo）」。切换次级导航时主内容区有过渡。

【必须照抄的字段/接口】
- // GET /api/v1/namespaces → [ops, payment, risk]

请输出完整 HTML，重点是导航切换流畅、角色分组清晰。
```

---

## 6. 使用建议（给用这份文档的人）

1. **首次生成**：建议先做 **C2 演示页** + **C1 大盘**，最能对外展示。
2. **把设计权交给设计侧**：生成时可以让 AI 先自行确定一套设计语言（或显式调用 UI 设计 skill 如 `ui-ux-pro-max`），然后贯穿所有页面，避免各页风格分裂。本文档不规定视觉，正是为了给设计发挥空间。
3. **串联演示**：最后用第 5 节全局骨架把所有页面串起来，作为完整 demo 投屏/录屏。
4. **字段/枚举/接口路径不要改**：这些是对齐真实后端的硬约束，AI 生成时不要让它自由改名，否则后续接真实后端要返工。视觉可以自由发挥，**业务字段不可以**。
5. **明暗基调**：本文档明确倾向**浅色为主**；如需暗色模式，由设计侧作为可选主题提供，不作为默认强制基调。

---

## 7. 待决策项（沿用页面规划原文档）

1. 真实后端接口补齐顺序（见 `2026-07-19-controlplane-api-audit.md` §6 B5 批次）—— demo 不依赖，但接真实数据时需要。
2. 告警 ack/resolve 是否纳入一期（依赖 ADR-0005）。
3. 跨 namespace 汇总（C3）是否开放（与 ADR-0002 冲突）—— 本次未为此页生成提示词。
4. 实时推送是否需要（demo 用定时器模拟，真实环境一期轮询够用）。

---

## 8. 变更记录

- **v1（2026-07-19）**：首版，含全局设计语言（深色基调、玻璃拟态、具体色值、招牌动效实现）。
- **v2（2026-07-19）**：剥离视觉硬编码 —— 配色/字体/组件库/动效实现交由设计侧（UI agent / skill）发挥；基调改为浅色为主、不强制深色；每页提示词改为「功能描述 + 视觉期望关键词」，仅保留业务字段/枚举/接口路径作为硬约束。
