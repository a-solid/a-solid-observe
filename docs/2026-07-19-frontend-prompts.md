# 前端页面 AI 生成提示词集（静态 HTML Demo）

**日期**：2026-07-19
**状态**：Review（待评审）
**用途**：本文档按页面分段，每段末尾给出**可直接复制给 AI 的提示词**，用于生成**纯静态 HTML demo 页面**（不接入真实后端，单文件可直接浏览器打开）。
**配套文档**：
- `2026-07-19-frontend-page-plan.md`（页面规划原始版）
- `2026-07-19-controlplane-api-audit.md`（后端接口现状，本文档据此对齐字段/路径/筛选维度，但 demo 内全部用静态 mock 数据）

---

## 0. 阅读说明（给用这份文档的人）

1. **本次只产出静态页面**，不接真实接口。但页面里的字段名、请求路径、筛选维度、状态枚举**必须符合项目现有接口设定**（见各页"接口字段对齐"小节），这样 demo 看起来像真的。
2. 每个页面 section 结构固定：
   - **页面目标**：一句话定位 + 服务的角色
   - **接口字段对齐**：该页消费的接口路径 + 关键字段/枚举（demo 里 mock，但要照抄这些字段）
   - **布局结构**：页面分区
   - **招牌动效**：该页的核心视觉效果
   - **📋 复制这段给 AI**：生成该页的完整提示词（自包含，AI 不需要读本文档其他部分也能生成）
3. **第 1 节"全局设计语言"是所有页面的视觉基线**。建议第一次生成页面时，把第 1 节 + 目标页一起喂给 AI；之后生成的页面应保持与第 1 节一致。

---

## 1. 全局设计语言（所有页面共用基线）

### 1.1 视觉基调
- **深色科技感**：背景深靛蓝渐变（`#0B1020` → `#131A2E`），可叠加极淡的网格纹理或星点。
- **玻璃拟态卡片**（glassmorphism）：`background: rgba(255,255,255,0.04)`，`backdrop-filter: blur(12px)`，`border: 1px solid rgba(255,255,255,0.08)`，圆角 `16px`，柔和投影。
- **强调色**：流光主色青→紫渐变（`#22D3EE` → `#A78BFA`）；次级强调 `#34D399`（绿）。
- **状态/严重度色板**：
  - severity：CRITICAL `#F43F5E`、WARNING `#F59E0B`、INFO `#38BDF8`
  - status（pipeline）：PUBLISHED `#34D399`、DRAFT `#F59E0B`、ARCHIVED `#64748B`
  - 执行结果：SUCCESS `#34D399`、SHORT_CIRCUITED `#F59E0B`、FAILED `#F43F5E`
- **字体**：系统无衬线（`Inter` / `-apple-system`），数字用等宽（`JetBrains Mono` / `ui-monospace`）突出"数据感"。
- **排版**：大量留白，标题大字号 + 细字重，数字用粗等宽。

### 1.2 招牌动效库（贯穿全产品）
> **核心隐喻**：数据像流体在"管道"中流动。一条贯穿全产品的溯源链路：
> ```
> Source ──▶ Subscription ──▶ Pipeline ──▶ Alert
> (CDC/CRON/API)  (条件过滤)    (check/emit)  (告警产出)
> ```
> 这条链路在不同页面以不同形态出现（完整粒子流动 / 静态高亮 / 分叉预览）。

**动效 1 — 流光粒子（管道流动）**
- 实现：SVG `<path>` + `stroke-dasharray` + `stroke-dashoffset` 动画（CSS `@keyframes`），让虚线"流动"。
- 粒子：沿 path 移动的小圆点（`<circle>` + `animateMotion` 或 CSS offset-path）。
- 用途：C2 Demo 招牌播放、B2 溯源链路、A5 配置预览。

**动效 2 — 数字 count-up**
- 页面加载/数据刷新时，hero 数字从 0 滚动到目标值（JS 简单插值，~800ms ease-out）。
- 用途：所有 KPI 卡片。

**动效 3 — 卡片渐入**
- 卡片依次淡入 + 上浮（`opacity 0→1`，`translateY 12px→0`，stagger 60ms）。

**动效 4 — 呼吸光晕（severity / status 指示）**
- CRITICAL 告警卡片左缘红光呼吸（`box-shadow` 脉冲，2s 循环）。
- PUBLISHED 状态点稳定绿光，DRAFT 琥珀色慢呼吸，ARCHIVED 灰色静止。

**动效 5 — 实时滑入（图表/列表）**
- 折线新数据点从右侧滑入；新告警卡片从顶部滑入 + 闪一下白光。
- 暗示"实时"，即使 demo 是静态的，也用 `setInterval` 触发一次"假装新数据"的动画。

**动效 6 — hover 微交互**
- 卡片 hover 上浮 4px + 流光边框（conic-gradient 边框旋转）。

### 1.3 通用组件
- **顶部导航条**：左 logo，中 namespace 切换器（下拉，mock 值：`ops` / `payment` / `risk`），右 角色页签（配置 / 告警 / 大盘）+ 时间范围选择器。
- **KPI 卡片**：标题 + 大数字（count-up）+ 趋势小箭头 + 底部 mini sparkline。
- **数据表格**：表头粘性，行 hover 高亮，severity/status 用徽标（badge）。
- **空状态**：居中插画 + 引导文案，不丑。
- **Toast / 抽屉**：点击管道节点展开详情用右侧抽屉（drawer）滑入。

### 1.4 技术约束（写给 AI 的硬性要求）
- **纯静态 HTML**：每个页面是一个**自包含的 `.html` 文件**，可直接双击在浏览器打开。
- **CDN 引入**：Tailwind CSS（play CDN）、ECharts（图表）、GSAP 或纯 CSS（动效）。不依赖构建工具、不依赖 npm。
- **mock 数据**：写在 `<script>` 里的 JS 对象/数组，字段名严格照抄"接口字段对齐"。
- **响应式**：至少桌面端（≥1280px）完美，平板端不崩。
- **不造接口**：demo 里不真的发请求；如需体现"接口路径"，在代码注释里标注 `// 接口: GET /api/v1/alerts?namespace=ops&severity=CRITICAL`。
- **性能**：粒子/流光用 CSS/GPU 友好属性，避免卡顿。

### 1.5 招牌动效复用映射表（速查）

| 场景 | 主方案 | 关键动效 |
|---|---|---|
| 告警溯源（B2/C2） | 横向管道 + 粒子流动 | 动效 1 |
| 执行历史（B3） | 时间轴泳道 | 动效 5（新行滑入）、动效 4（失败呼吸） |
| 失败执行（B4） | 节点诊断图（监护仪波形） | 自定义波形动画 |
| 大盘趋势（C1） | 实时心电图折线 + 生长柱状 | 动效 2、动效 5 |
| 告警列表（B1） | 卡片瀑布 + severity 光晕 | 动效 4、动效 5 |
| 配置列表（A1/A4） | 卡片网格 + 状态呼吸灯 | 动效 3、动效 4、动效 6 |
| 干跑（A2） | 示波器波形 | 自定义波形 |
| 多 pipeline 绑定（A5） | 一对多分叉管道 | 动效 1（分叉版） |

---

## 2. C 类 — Manager / 对外展示页

### 2.1 C1 大盘首页

**页面目标**：对外展示核心价值的英雄页。一眼看清"今天系统监测了多少、抓到多少问题、成功率多高"。面向 Manager，截图录屏首选。

**接口字段对齐（demo 内 mock，字段照抄）**：
- `GET /api/v1/stats/alerts?namespace=&from=&to=` → `{ bySeverity: {CRITICAL,WARNING,INFO}, byStatus: {FIRING,RESOLVED}, total }`
- `GET /api/v1/stats/alerts/timeseries?namespace=&from=&to=&bucket=1h` → `[{ bucketStart, count }]`
- `GET /api/v1/stats/executions?namespace=&from=&to=` → `{ success, failed, short_circuited, successRate }`
- hero 计数：今日告警总数、FIRING 数、CRITICAL 数、pipeline 成功率。

**布局结构**：
1. **顶部 hero 管道条**：横向 `Source → Subscription → Pipeline → Alert` 管道（静态版，流光持续），上方叠加"今日 N 个事件流经、M 个触发告警"实时计数。
2. **4 张 KPI 卡片**：今日告警总数 / FIRING / CRITICAL / 执行成功率。数字 count-up。
3. **趋势区（两列）**：
   - 左：告警趋势折线（心电图式，新点右滑入），按 severity 堆叠。
   - 右：执行吞吐柱状（每根从底部生长），success/failed 双色。
4. **分布区（两列）**：告警按 severity 饼图、按 team 柱状。
5. **Top N 表格**：最活跃 pipeline Top 5、最频繁告警 fingerprint Top 5。

**招牌动效**：
- hero 管道持续流光（动效 1）。
- KPI count-up（动效 2）。
- 折线每 5s 假装新增一个点滑入（动效 5），柱状生长。
- 卡片渐入（动效 3）。

**📋 复制这段给 AI：**

```
你是一位资深前端工程师 + 视觉设计师。请生成一个"可观测性平台大盘首页"的纯静态 HTML demo 页面，用于给客户 Manager 对外展示，必须非常有视觉冲击力、抓眼球，不能像运维工具。

技术要求：
- 单个自包含 .html 文件，可直接浏览器打开。
- CDN 引入：Tailwind CSS (play CDN)、ECharts。
- 深色科技感：背景深靛蓝渐变 #0B1020→#131A2E；玻璃拟态卡片（rgba白 + backdrop-blur + 细边框 + 圆角16px）。
- 强调色：流光用青→紫渐变 #22D3EE→#A78BFA；severity 色 CRITICAL #F43F5E / WARNING #F59E0B / INFO #38BDF8；成功 #34D399。
- 数字用等宽字体。

页面布局（从上到下）：
1. 顶部导航：左 logo「A-Solid Observe」，中 namespace 下拉（mock 值 ops/payment/risk），右时间范围选择器（今日/7天/30天，默认今日）。导航下方紧贴一条「溯源管道」hero 条：横向 SVG 管道，4 段节点 Source → Subscription → Pipeline → Alert，节点用圆角胶囊，连接线是带流光动画的渐变路径（stroke-dashoffset 持续流动 + 几个小圆点粒子沿线移动）。管道上方文字「今日 1,284 个事件流经管道 · 触发 47 条告警」，数字 count-up。
2. 4 张 KPI 卡片横排：今日告警总数 64 / FIRING 8 / CRITICAL 12 / 执行成功率 98.4%。每张卡片：玻璃质感、大数字 count-up 动画（0→目标值，800ms）、右下角 mini sparkline、左上角标题。FIRING 和 CRITICAL 卡片左缘带对应颜色呼吸光晕。
3. 两列图表区：
   左 ECharts 折线图「告警趋势（按小时）」：堆叠面积图，3 条线 CRITICAL/WARNING/INFO，平滑曲线，渐变填充，tooltip 玻璃质感。每 5 秒用 JS 假装 push 一个新点到数组末尾，让折线从右侧滑入新数据（setInterval + setOption）。
   右 ECharts 柱状图「执行吞吐（按小时）」：双色柱（success 绿 / failed 红），柱子加载时从底部生长动画（ECharts animation）。
4. 两列分布区：左饼图「告警按 severity 分布」；右横向柱状「告警按 team 分布」。
5. 底部两个 Top5 表格卡片：最活跃 pipeline、最频繁告警 fingerprint。表格行 hover 高亮。

动效要求：
- 所有卡片渐入（stagger 60ms，opacity 0→1 + translateY 12px→0）。
- hero 管道流光必须持续、丝滑。
- KPI 数字 count-up。
- 整体给人「系统正在实时运转」的感觉。

数据：全部写在 <script> 的 mock 数组里，字段照抄真实接口约定（bySeverity/byStatus/timeseries 的 bucketStart+count/successRate）。不要发真实请求。代码里用注释标注对应接口路径，例如 // GET /api/v1/stats/alerts?namespace=ops&from=...&to=...
响应式：桌面端 1280px+ 完美，窄屏不崩即可。
请直接输出完整 HTML。
```

---

### 2.2 C2 对外 Demo / 演示页（招牌）

**页面目标**：这是全产品的"招牌动作"。一个事件从 Source 注入，沿管道流过 Subscription、Pipeline，最后在末端炸开成一个 Alert 卡片。适合投屏/录屏/截图。可选"演示模式"按钮自动播放。

**接口字段对齐**：
- `POST /api/v1/events`（提交事件，返回 eventId）— demo 里假装提交。
- `GET /api/v1/alerts/{id}`（告警详情：severity/labels/annotations/startsAt）— 末端炸开的卡片字段照抄。

**布局结构**：
1. **居中超大舞台**：一条横向大管道占满主区域，4 段节点 Source → Subscription → Pipeline → Alert。
2. **播放控制**：底部「▶ 演示模式」按钮 + 步进按钮。
3. **每段节点可点击**：点击弹出右侧抽屉显示该实体详情（source 配置 / subscription 条件 / pipeline 定义 / alert 元信息）。
4. **末端 Alert 卡片**：粒子到达末端时"爆开"成一个告警卡，带 severity 色光晕。

**招牌动效**：
- 完整粒子流动播放（动效 1 升级版）：
  1. 事件粒子从 Source 注入（弹入 + 光环）。
  2. 沿连接线流向 Subscription（粒子拖尾）。
  3. 在 Subscription 节点短暂"过滤"动画（几个粒子被弹开 = 不满足条件，主粒子通过）。
  4. 流向 Pipeline 节点，节点处理时脉冲发光。
  5. 流向末端，"爆开"成 Alert 卡片（粒子炸开 + 卡片 scale-in）。
- 整段动画 ~3 秒，可循环。
- 节点 hover 高亮，点击展开抽屉（动效：抽屉从右滑入）。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 动效设计师。请生成一个"可观测性平台对外演示页"，这是产品的招牌 demo，要让人一看就 wow。纯静态 HTML，单文件，浏览器直接打开。

技术：CDN 引入 Tailwind + GSAP（动效用 GSAP，更丝滑）。深色科技感（#0B1020 渐变背景）、玻璃拟态、青紫流光配色（#22D3EE→#A78BFA）。

核心舞台：页面中央一条占满主区域的横向「溯源管道」，4 个节点等距排列，用大号圆角玻璃胶囊节点，每个节点有图标 + 名称：
  ① Source（CDC/CRON/API，标「CDC 事件」）
  ② Subscription（标「orders 表 · 金额>10000」）
  ③ Pipeline（标「check: 高额订单告警」）
  ④ Alert（末端，初始空，播放后填充）
节点之间用粗渐变路径连接（SVG path），路径默认半透明，激活时流光亮起。

动画流程（点「▶ 演示模式」触发，整段约 3 秒，可循环）：
1. 一个发光粒子从 Source 节点弹出（带光环扩散）。
2. 粒子拖尾沿路径流向 Subscription 节点（用 GSAP motionPath 或 offset-path），路径被点亮（流光从左到右）。
3. 到达 Subscription 节点时，额外 2-3 个灰色粒子从节点被"弹飞/淡出"（表示被条件过滤），主粒子继续。
4. 粒子流向 Pipeline 节点，节点强烈脉冲发光 0.5 秒（表示在判定）。
5. 粒子继续到末端 Alert 节点，粒子炸开成放射状，同时一个告警卡片 scale-in 弹出（severity CRITICAL 红色光晕呼吸），卡片显示：severity 徽标、fingerprint、labels.entity「orders」、annotations「金额 ¥58,200 超阈值」、startsAt 时间。
6. 全程节点序号高亮跟随粒子位置。

交互：
- 每个节点 hover 放大 + 流光边框；点击节点 → 右侧抽屉滑入，显示该节点详情（source 配置表 / subscription 条件树 / pipeline JSON 摘要 / alert 元信息），抽屉用 mock 数据。
- 底部「▶ 演示模式」按钮（点一次播放一次）、「自动循环」开关。

数据全部 mock 写在 script 里，字段照抄真实接口（alert 卡片字段：severity/fingerprint/labels.entity/annotations/startsAt；代码注释标 // POST /api/v1/events → eventId；// GET /api/v1/alerts/{id}）。
请输出完整 HTML，重点是把动画做得非常流畅、有冲击力。
```

---

## 3. B 类 — 告警查看处理页

### 3.1 B1 告警列表页（值班首屏）

**页面目标**：值班/运维一进来就要看见"现在有什么火在烧"。CRITICAL 置顶、光晕呼吸吸引注意，新告警滑入。

**接口字段对齐**：
- `GET /api/v1/alerts?namespace=&status=&team=&pipeline_id=&severity=&from=&to=&limit=` → 列表。
- 字段：`severity`（CRITICAL/WARNING/INFO）、`status`（FIRING/RESOLVED）、`fingerprint`、`labels.entity`、`startsAt`、`lastSeenAt`、`dedupCount`、`team`、`pipelineId`。

**布局结构**：
1. **顶部筛选条**：severity 多选、status、team、pipeline、时间范围。筛选条用玻璃胶囊样式。
2. **左侧 severity 分组栏**：按 severity 堆叠分组计数（CRITICAL 12 / WARNING 47 / INFO 5），点击筛选。
3. **主区告警卡片瀑布**：每条告警一张窄卡片，CRITICAL 永远置顶。
4. **卡片内容**：左缘 severity 色条 + 呼吸光晕（FIRING+CRITICAL）、severity 徽标、fingerprint（等宽）、labels.entity、startsAt 相对时间、dedupCount、点击进入详情。

**招牌动效**：
- CRITICAL+FIRING 卡片左缘红光呼吸（动效 4）。
- 页面加载后 3 秒，假装从顶部滑入一条新告警 + 闪白光（动效 5），暗示实时。
- 卡片渐入 stagger（动效 3）。
- hover 上浮 + 流光边框（动效 6）。

**📋 复制这段给 AI：**

```
你是资深前端工程师。生成「可观测性平台告警列表页」纯静态 HTML demo（单文件，浏览器直开）。这是值班人员首屏，要让人一眼看到"最严重的问题"。

技术：CDN Tailwind + 纯 CSS 动效。深色科技感（#0B1020 渐变），玻璃拟态卡片，severity 色 CRITICAL #F43F5E / WARNING #F59E0B / INFO #38BDF8。

布局：
1. 顶部导航 + 筛选条（玻璃胶囊样式）：severity 多选标签（CRITICAL/WARNING/INFO）、status 下拉（FIRING/RESOLVED/全部）、team、pipeline、时间范围（最近1h/24h/7d）。筛选条要好看，不是原生 select。
2. 左侧窄栏 severity 分组：三个可点击的色块卡片，显示 CRITICAL 12 / WARNING 47 / INFO 5，点击高亮筛选主区。
3. 主区：告警卡片纵向列表（不是死板表格，是卡片）。每张卡片：
   - 左缘 4px 色条（severity 色）。
   - CRITICAL 且 FIRING 的卡片，左缘加该色呼吸光晕（box-shadow 脉冲 2s 循环）。
   - 卡片内容：severity 徽标、fingerprint（等宽字体）、labels.entity（如 orders-db）、startsAt（显示"3 分钟前"）、dedupCount（×5）、team、所属 pipeline 名。
   - hover：上浮 4px + 流光渐变边框（conic-gradient 旋转）。
4. mock 8-12 条告警数据，CRITICAL+FIRING 排最前，RESOLVED 的卡片降低不透明度。

动效：
- 卡片渐入（stagger）。
- 页面加载 3 秒后，用 JS 在列表顶部"滑入"一条新 CRITICAL 告警 + 闪一下白光，制造实时感（setInterval 可关）。
- 顶部一个「实时」绿色脉冲点，表示连接中。

数据 mock 写 script，字段照抄：severity/status/fingerprint/labels.entity/startsAt/lastSeenAt/dedupCount/team/pipelineId。注释标 // GET /api/v1/alerts?namespace=ops&severity=CRITICAL&from=...&to=...
输出完整 HTML。
```

---

### 3.2 B2 告警详情页（排障核心 + 溯源管道复用）

**页面目标**：点进一条告警，顶部直接展示它的"来龙去脉"管道，中间看证据，底部看状态时间线。

**接口字段对齐**：
- `GET /api/v1/alerts/{id}?namespace=` → 元信息（severity/labels/annotations/startsAt/lastSeenAt/endsAt/resolvedAt/status/dedupCount）。
- `GET /api/v1/alerts/{id}/evidence?namespace=` → 证据（节点输出 JSON/表格）。
- 关联 execution：跳到 `GET /api/v1/executions/{id}`。
- 写操作（demo 里按钮可见但 mock）：`POST /alerts/{id}/ack`、`POST /alerts/{id}/resolve`。

**布局结构**：
1. **顶部溯源管道条**：静态高亮版 `Source → Subscription → Pipeline → Alert`，每个节点填该告警的真实溯源值（如 Pipeline: 「高额订单告警 v3」），点击节点跳转/展开该实体。
2. **告警元信息卡**：severity 大徽标、fingerprint、labels、annotations、时间线（starts → lastSeen → ends/resolved）。
3. **证据区**：`evidence` 节点输出，用 JSON 高亮查看器 + 可切换表格视图。
4. **状态流转时间线**：FIRING → (ack) → RESOLVED 的 dedup 演化（每个时间点一条）。
5. **处置动作条**：ack / resolve / silence 按钮（mock，点击给 toast）。

**招牌动效**：
- 顶部管道节点依次点亮（动效 1 静态版，加载时流光从 Source 走到 Alert 一次）。
- 时间线节点渐入 + 连线生长。
- 证据 JSON 逐行渐入。

**📋 复制这段给 AI：**

```
你是资深前端工程师。生成「告警详情页」纯静态 HTML demo（单文件）。这是排障核心页，顶部要用溯源管道展示这条告警"从哪来"。

技术：CDN Tailwind + 纯 CSS。深色科技感，玻璃拟态，severity 色 CRITICAL #F43F5E（本条是 CRITICAL）。

布局（上到下）：
1. 顶部溯源管道条（横向，4 节点）：Source「CDC: orders 表 INSERT」→ Subscription「高额订单监控」→ Pipeline「check 高额订单告警 v3」→ Alert「本告警」。每个节点是圆角玻璃胶囊，带该实体的图标和名称。页面加载时，一条流光从 Source 沿路径流到 Alert（播放一次，~1.5s），之后路径保持半亮高亮，表示这条告警的实际溯源链。每个节点可点击（hover 放大 + 流光边框）。
2. 告警元信息卡：左上 CRITICAL 大徽标（红光呼吸）、fingerprint（等宽 a1b2c3...）、labels（entity=orders, team=payment, severity=CRITICAL 标签云）、annotations（描述文本）。右侧时间线小图：startsAt → lastSeenAt → endsAt/resolvedAt，用时间轴节点表示，已发生节点亮，未来节点灰。
3. 证据区（卡片）：标题「Pipeline 节点输出（evidence）」。上半 JSON 查看器（语法高亮，mock 一段 check 节点的输出，含 matched:true, value:58200, threshold:10000），下半可切换「表格视图」tab。JSON 逐行渐入。
4. 状态流转时间线：垂直时间线，节点：14:28:01 FIRING（首次）→ 14:28:15 dedup ×3 → 14:30:00 ack by alice → 14:35:22 RESOLVED。每个节点带时间、动作、操作人。连线生长动画。
5. 底部固定操作条：按钮「Acknowledge」「Resolve」「Silence 1h」（玻璃按钮，CRITICAL 的 Resolve 按钮用红色高亮）。点击任一按钮弹 toast「已 ack（mock）」。

数据 mock 写 script，字段照抄：severity/labels/annotations/startsAt/lastSeenAt/endsAt/resolvedAt/status/dedupCount/fingerprint；evidence 为节点输出对象。注释标 // GET /api/v1/alerts/{id}?namespace=ops 和 // GET /api/v1/alerts/{id}/evidence?namespace=ops。
输出完整 HTML，溯源管道是这页的视觉重点。
```

---

### 3.3 B3 执行历史页（时间轴泳道）

**页面目标**：看一段时间内 pipeline 跑了多少次、成功/短路/失败各多少、每次耗时。用时间轴泳道，比表格更有"流水"感。

**接口字段对齐**：
- `GET /api/v1/executions?namespace=&pipeline_id=&status=&from=&to=&limit=` → `triggerType`、`triggerEvent` 摘要、`status`（SUCCESS/SHORT_CIRCUITED）、`durationMs`、`startedAt`。

**布局结构**：
1. **顶部筛选 + 统计胶囊**：pipeline 选择、status 多选、时间范围；旁边 3 个胶囊：SUCCESS 1,204 / SHORT_CIRCUITED 87 / （failed 跳 B4）。
2. **时间轴泳道主区**：每条执行一行泳道，从上到下按时间倒序：
   - 左：时间戳 + triggerType 图标（CDC 脉冲 / CRON 时钟 / API 闪电）。
   - 中：一条水平进度条，宽度=durationMs 比例，颜色=结果（绿/黄/红）。
   - 右：状态徽标 + 耗时 + 点击展开。
3. **展开行**：点击某行，下方滑出 triggerEvent 摘要 JSON。

**招牌动效**：
- 新执行从顶部滑入（动效 5，setInterval 假装）。
- 进度条加载时从左"填充"到目标宽度。
- SHORT_CIRCUITED 行末尾一个黄色闪电图标闪烁；失败行（如果有）红色脉冲。

**📋 复制这段给 AI：**

```
你是资深前端工程师。生成「pipeline 执行历史页」纯静态 HTML demo（单文件）。用「时间轴泳道」而不是死板表格，体现执行流水感。

技术：CDN Tailwind + 纯 CSS。深色科技感，玻璃卡片。结果色 SUCCESS #34D399 / SHORT_CIRCUITED #F59E0B / FAILED #F43F5E。

布局：
1. 顶部导航 + 筛选条（玻璃胶囊）：pipeline 下拉、status 多选（SUCCESS/SHORT_CIRCUITED）、时间范围。右侧 3 个统计胶囊卡：SUCCESS 1,204（绿）、SHORT_CIRCUITED 87（黄）、平均耗时 320ms。
2. 主区「时间轴泳道」：每条执行一行（mock 10-15 条），结构：
   - 左列：时间戳（14:32:01，等宽）+ triggerType 图标徽标（CDC=脉冲波纹图标 / CRON=时钟 / API=闪电），不同颜色。
   - 中列：水平进度条，宽度按 durationMs 比例（最长那条占满），颜色按 status，条上标注「320ms」。加载时进度条从左填充到目标宽度（CSS transition）。
   - 右列：status 徽标 + 时长。SHORT_CIRCUITED 末尾黄色闪电图标轻闪。
   - 行 hover 高亮 + 可点击展开。
3. 点击某行，下方滑出一个展开面板（accordion），显示 triggerEvent 摘要（mock JSON：触发事件的关键字段）。
4. 顶部「实时」绿点脉冲。

动效：
- 行渐入 stagger。
- 加载 4 秒后，JS 从顶部滑入一条新执行行（setInterval），制造流水感。
- 进度条填充动画。

数据 mock 写 script，字段照抄：triggerType/triggerEvent/status(SUCCESS,SHORT_CIRCUITED)/durationMs/startedAt/pipelineId。注释标 // GET /api/v1/executions?namespace=ops&status=SUCCESS&from=...&to=...
输出完整 HTML。
```

---

### 3.4 B4 失败执行页（节点诊断图 / 监护仪）

**页面目标**：定位失败"病灶"。因为 95% pipeline 是单节点，失败就是这一个节点的问题，用"监护仪/诊断图"风格可视化"在哪一步崩的"。

**接口字段对齐**：
- `GET /api/v1/failed-executions?namespace=&pipeline_id=&limit=` → `errorType`、`nodeName`、`errorMessage`、`stackTrace`、`status`（PENDING/RESOLVED/IGNORED）、`createdAt`。
- 重试（demo 按钮可见 mock）：`POST /failed-executions/{id}/retry`。

**布局结构**：
1. **顶部筛选 + 失败列表**：errorType 分组胶囊。
2. **左侧失败列表**：每条卡片，errorType 徽标 + nodeName + 状态（PENDING 橙呼吸 / RESOLVED 灰 / IGNORED 灰）+ createdAt。
3. **右侧诊断面板**（选中某条后）：**节点诊断图** —— 输入脉冲 → 节点处理波形 → 输出。
   - 一条横向"信号通道"：左边输入信号（脉冲波），中间节点处理区（波形抖动），右边输出。
   - 失败点用红色标注在波形的某段（input 解析 / check 求值 / emit？根据 errorType）。
   - 像医疗监护仪的栅格背景 + 移动扫描线。
4. **错误详情**：errorMessage + 可展开 stackTrace + 「一键重试」按钮。

**招牌动效**：
- 监护仪栅格背景 + 一条扫描线从左到右循环移动。
- 输入脉冲持续跳动（模拟心跳）。
- 失败点红色波形脉冲。
- 选中失败卡片时，诊断图重新"播放"一次（输入→崩溃）。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 动效设计师。生成「失败执行诊断页」纯静态 HTML demo（单文件）。这页要像「医疗监护仪/示波器」定位 pipeline 失败的病灶，有科技诊断感，不要做成普通错误日志页。

技术：CDN Tailwind + 纯 CSS/SVG。深色科技感，背景偏黑 #080B16，诊断图区有监护仪栅格背景。

布局（左右两栏）：
左栏（失败列表，~35% 宽）：
- 顶部 errorType 分组胶囊：NPE 3 / Timeout 2 / EmitError 1 / ... 可点击筛选。
- 失败卡片列表（mock 5-6 条），每张：errorType 徽标（红/橙色调）、nodeName（如 check-amount）、pipeline 名、status 徽标（PENDING=橙呼吸光晕 / RESOLVED=灰 / IGNORED=灰）、createdAt 相对时间。PENDING 卡片呼吸光晕。选中态高亮流光边框。

右栏（诊断面板，~65% 宽，选中某条失败后显示）：
- 顶部标题「节点诊断 · {nodeName}」+ errorType + 一键重试按钮（玻璃按钮，点击 toast「已重试（mock）」）。
- 核心是一张「节点诊断图」：横向信号通道，三段：
  ① 左「输入信号」：一段脉冲波形（SVG path），像心跳，持续跳动（CSS 动画），绿色，表示事件正常进入。
  ② 中「节点处理 {nodeName}」：波形进入节点后开始抖动处理（波形变密/不规则），节点框玻璃质感 + 内部图标。
  ③ 右「输出」：波形在「失败点」突然变成一条红色直线（flatline 平直线）+ 红色 ✗。失败点位置根据 errorType 标注（如 NPE 标在 check 求值段，EmitError 标在输出段），用一个红色脉冲标记 + 标签「NPE @ check」。
- 整个诊断图区有监护仪栅格背景（细网格），一条扫描线从左到右循环扫过（CSS translateX 动画），增强诊断感。
- 选中不同失败卡片时，诊断图重新"播放"一次（输入脉冲 → 处理 → 崩溃成红线）。
- 诊断图下方：errorMessage（红色高亮代码块）+ 可展开的 stackTrace（等宽字体折叠面板）。

数据 mock 写 script，字段照抄：errorType/nodeName/errorMessage/stackTrace/status(PENDING,RESOLVED,IGNORED)/createdAt/pipelineId。注释标 // GET /api/v1/failed-executions?namespace=ops 和 // POST /failed-executions/{id}/retry。
输出完整 HTML，诊断图是这页的灵魂，要做得有冲击力。
```

---

## 4. A 类 — Pipeline / Subscription 配置页

### 4.1 A1 Pipeline 列表页（卡片网格 + 状态呼吸灯）

**页面目标**：维护者看自己有哪些 pipeline、状态如何。卡片网格比表格更"产品感"。

**接口字段对齐**：
- `GET /api/v1/namespaces/{ns}/pipelines` → `name`、`team`、`application`、`status`（DRAFT/PUBLISHED/ARCHIVED）、`currentVersion`、`updatedAt`、`labels`。

**布局结构**：
1. 顶部导航 + 「+ 新建 Pipeline」按钮。
2. 筛选条：status、team、application、搜索框。
3. **卡片网格**（3 列）：每张卡片 = 一个 pipeline，含 name、description、team/application 标签、右上角**状态呼吸灯**、currentVersion、updatedAt、底部 mini sparkline（近期执行）。
4. 卡片操作：查看 / 编辑 / 归档（hover 显示）。

**招牌动效**：
- 状态呼吸灯（动效 4）：PUBLISHED 绿稳定 / DRAFT 琥珀慢呼吸 / ARCHIVED 灰静止。
- 卡片渐入 stagger（动效 3）。
- hover 上浮 + 流光边框（动效 6）。

**📋 复制这段给 AI：**

```
你是资深前端工程师。生成「Pipeline 列表页」纯静态 HTML demo（单文件）。用卡片网格而非表格，要有产品感，不像后台 CRUD。

技术：CDN Tailwind + 纯 CSS。深色科技感 #0B1020，玻璃拟态卡片，青紫流光强调色。

布局：
1. 顶部导航 + 页面标题「Pipelines」+ 右侧「+ 新建 Pipeline」流光按钮。
2. 筛选条（玻璃胶囊）：status 多选（DRAFT/PUBLISHED/ARCHIVED）、team、application、搜索框。
3. 卡片网格（3 列，响应式），mock 9 张卡片。每张卡片：
   - 玻璃质感，圆角 16px。
   - 左上 pipeline name（粗体）+ 描述一行。
   - 右上角状态呼吸灯（小圆点 + 文字）：PUBLISHED=绿色稳定光、DRAFT=琥珀色慢呼吸、ARCHIVED=灰色静止。
   - 中部标签：team（payment）、application（order-service）小胶囊。
   - 底部一行：currentVersion「v3」+ updatedAt「2 小时前」+ mini sparkline（mock 近期执行次数小折线）。
   - hover：整卡上浮 4px + 流光渐变边框（conic 旋转）。
   - hover 时右上角浮出操作图标：查看 / 编辑 / 归档。
4. 状态分布要有视觉差异：大部分 PUBLISHED，2 张 DRAFT，1 张 ARCHIVED（降低不透明度）。

动效：卡片渐入 stagger 60ms；状态呼吸灯；hover 上浮 + 流光边框。
数据 mock 写 script，字段照抄：name/team/application/status(DRAFT,PUBLISHED,ARCHIVED)/currentVersion/updatedAt/labels。注释标 // GET /api/v1/namespaces/{ns}/pipelines。
输出完整 HTML。
```

---

### 4.2 A2 Pipeline 编辑器 + 干跑面板（示波器）

**页面目标**：左侧编辑 pipeline（JSON / 可视化），右侧干跑结果像示波器实时播放。即使单节点也要好看。

**接口字段对齐**：
- `POST /api/v1/validate/pipeline` → 校验错误列表。
- `POST /api/v1/validate/dry-run` → outcome + 每节点输出 + 触发的告警。
- 版本：`POST .../versions`、`POST .../versions/{v}/publish`。

**布局结构**：
1. **顶部工具条**：pipeline 名 + 版本号 + 「校验」「干跑」「保存版本」「发布」按钮。
2. **左栏（编辑）**：tab 切换「JSON 编辑器」「可视化节点」。可视化：单节点 pipeline 就一个大节点卡片（check/emit/script），可配字段。
3. **右栏（校验 + 干跑面板）**：
   - 上：校验结果（错误列表，绿色✓通过 / 红色错误行）。
   - 下：**示波器干跑区** —— 输入事件波形 → 节点处理 → 输出波形 → 是否触发告警。点「▶ 干跑」播放一次。
4. **底部**：版本操作。

**招牌动效**：
- 干跑示波器：输入波形注入 → 节点处理波形 → 输出波形（matched 则告警图标亮起）。
- 校验通过时绿色✓弹出动画；错误行抖动。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 动效设计师。生成「Pipeline 编辑器 + 干跑面板」纯静态 HTML demo（单文件）。左右分栏：左编辑，右校验+干跑。干跑结果用「示波器」风格可视化。

技术：CDN Tailwind + 纯 CSS/SVG。深色科技感。

布局：
1. 顶部工具条：pipeline 名「高额订单告警」+ 版本「draft v4」+ 按钮「校验」「干跑」「保存版本」「发布」（发布用青紫流光高亮）。
2. 左栏（~45%）编辑区：顶部 tab「可视化」「JSON」。
   - 可视化视图：单节点 pipeline 就显示一个大节点卡片（类型 check），节点内可配字段（如 threshold: 10000, compare: GT），节点上方有「输入」入口小圆点，下方「输出」出口。简化即可，不用真编辑。
   - JSON 视图：一段 pipeline JSON 代码（语法高亮，mock）。
3. 右栏（~55%）：
   - 上半「校验结果」卡：默认显示✓「校验通过」（绿色对勾弹出动画），或 mock 1 条红色错误（某字段缺失，行抖动）。
   - 下半「干跑示波器」卡：一个「▶ 干跑」按钮 + 一个示例事件选择器。点干跑后，示波器播放：
     ① 输入波形：一段事件数据脉冲（绿色波形）从左注入。
     ② 节点处理：波形进入 check 节点，节点脉冲发光，波形经过判定。
     ③ 输出波形：matched=true 则输出波形继续 + 右侧告警图标亮起（红色脉冲）+ 弹出「触发告警 CRITICAL」标签；matched=false 则波形变灰 + 「未触发」。
     - 示波器有栅格背景 + 扫描线。
   - 示波器下方：节点输出 JSON（mock：{matched:true, value:58200, threshold:10000}）。
4. 底部：版本操作区「保存为新版本」「发布 v4」「查看历史」按钮 + 最近版本时间线缩略。

数据 mock 写 script。注释标 // POST /api/v1/validate/pipeline 和 // POST /api/v1/validate/dry-run。
输出完整 HTML，干跑示波器是这页的亮点。
```

---

### 4.3 A3 Pipeline 版本管理页

**页面目标**：看一个 pipeline 的所有版本、发布历史、版本 diff、一键回滚。

**接口字段对齐**：
- `GET .../pipelines/{name}/versions` → 版本号、status、definitionHash、publishedBy、时间。
- diff（demo mock）：`GET .../versions/{v1}/diff/{v2}`。
- 发布/归档：`POST .../versions/{v}/publish`、`POST .../versions/{v}/archive`。

**布局结构**：
1. 左：版本时间线（垂直，每版一个节点，PUBLISHED 高亮，当前版有「current」标记）。
2. 右：选中两版后的 **diff 视图**（左旧右新，增删行高亮）+ 「回滚到此版」按钮。

**招牌动效**：时间线节点渐入 + 连线生长；diff 行高亮滑入。

**📋 复制这段给 AI：**

```
你是资深前端工程师。生成「Pipeline 版本管理页」纯静态 HTML demo（单文件）。

技术：CDN Tailwind + 纯 CSS。深色科技感，玻璃卡片。

布局（左右）：
左栏（版本时间线，~30%）：
- 垂直时间线，每个版本一个节点（mock 5 版：v1-v5）。
- 节点显示：版本号、status 徽标（PUBLISHED 绿 / ARCHIVED 灰 / DRAFT 琥珀）、publishedBy、时间。
- 当前发布版有「current」流光标记 + 高亮。
- 节点渐入 + 连线生长动画。可勾选两个版本做 diff。

右栏（~70%）：
- 顶部：diff 标题「v3 → v4 变更」。
- 双栏 diff 视图：左 v3、右 v4，代码行对齐，删除行红底、新增行绿底，mock 几处字段变更（如 threshold 10000→20000）。变更行滑入高亮。
- 底部按钮：「回滚到 v3」「发布 v4」「归档 v3」（玻璃按钮，回滚用警示色）。

数据 mock 写 script，字段照抄：version/status/definitionHash/publishedBy/时间。注释标 // GET /api/v1/namespaces/{ns}/pipelines/{name}/versions 和 // GET .../versions/{v1}/diff/{v2}。
输出完整 HTML。
```

---

### 4.4 A4 Subscription 列表页

**页面目标**：看有哪些订阅、什么源、绑了哪些 pipeline。重点体现 `pipelineIds:[a,b,c]` 一个订阅绑多 pipeline。

**接口字段对齐**：
- `GET /api/v1/namespaces/{ns}/subscriptions` → `name`、`sourceType`（CDC/CRON/API）、`pipelineIds:[]`、`actionType`（RUN/SCHEDULE/CANCEL）、`cronExpression`、`status`。

**布局结构**：
1. 筛选条：sourceType 分组（CDC/CRON/API tab）。
2. **卡片网格**：每张订阅卡，含 name、sourceType 图标、源配置摘要（CRON 显示 cron 表达式）、**绑定的 pipeline 数组**（用小胶囊横排展示 pipelineIds，多个时显示 `+N`）、actionType、status。

**招牌动效**：卡片渐入；sourceType 图标微动（CDC 脉冲 / CRON 转动 / API 闪电）；hover 流光边框。

**📋 复制这段给 AI：**

```
你是资深前端工程师。生成「Subscription 列表页」纯静态 HTML demo（单文件）。卡片网格。重点：一个 subscription 可绑定多个 pipeline（pipelineIds 数组），要清楚展示。

技术：CDN Tailwind + 纯 CSS。深色科技感，玻璃卡片。

布局：
1. 顶部导航 + 标题「Subscriptions」+「+ 新建」按钮。
2. sourceType 分组 tab：CDC / CRON / API（不同数量徽标）。
3. 卡片网格（2-3 列），mock 8 张卡片，每张：
   - 顶部：sourceType 图标徽标 + name。sourceType 图标微动：CDC=脉冲波纹、CRON=时钟缓慢转、API=闪电。
   - 源配置摘要：CDC 显示「mq + topic + table + opTypes」；CRON 显示 cron 表达式（等宽字体高亮，如 */5 * * * *）；API 显示 apiName。
   - 「绑定 Pipeline」区：横排小胶囊展示 pipelineIds，如 [高额订单告警] 单个，或 [告警A] [告警B] [+1] 多个（重点展示一两张绑 2-3 个 pipeline 的卡）。每个胶囊 hover 可点。
   - 底部：actionType 徽标（RUN/SCHEDULE/CANCEL）+ status。
   - hover：上浮 + 流光边框。
4. 卡片渐入 stagger。

数据 mock 写 script，字段照抄：name/sourceType(CDC,CRON,API)/pipelineIds(数组)/actionType(RUN,SCHEDULE,CANCEL)/cronExpression/status。注释标 // GET /api/v1/namespaces/{ns}/subscriptions。
输出完整 HTML，务必体现一个 subscription 绑多个 pipeline。
```

---

### 4.5 A5 Subscription 编辑页（条件树 + 一对多分叉管道预览）

**页面目标**：配置一个订阅：选 pipeline（可多个）→ 配源 → 条件编辑器（Condition 树）→ actionType。右侧实时**分叉管道预览**：Source → Subscription → 多个 Pipeline 分叉。

**接口字段对齐**：
- `POST/PUT /api/v1/namespaces/{ns}/subscriptions` → `pipelineIds:[]`、source 配置（CDC: mq/topic/db/table/opTypes；CRON: cronExpression/concurrent；API: apiName）、`fieldFilter`（Condition 树：AND/OR/Compare/In）、`actionType`。

**布局结构**：
1. 左栏表单（~55%）：
   - **Pipeline 选择**：多选器，选中的 pipeline 横排胶囊，可加可删（支持 `pipelineIds:[a,b,c]`）。
   - **源配置**：sourceType 切换 → 对应字段表单。
   - **条件编辑器**：`Condition` sealed 树可视化（AND/OR 容器节点 + Compare/In 叶子节点），可增删节点。这是这页重点。
   - **actionType** 选择。
2. 右栏预览（~45%）：**一对多分叉管道**：
   ```
   Source ──▶ Subscription ──┬─▶ Pipeline A ──▶ (告警)
                             ├─▶ Pipeline B ──▶ (告警)
                             └─▶ Pipeline C ──▶ (告警)
   ```
   实时反映左侧选的 pipelineIds 数量，每个分支流光。source/subscription 节点点击展开配置。

**招牌动效**：
- 分叉管道流光（动效 1 分叉版）：Source 到 Subscription 主干流动，到 Subscription 后分叉到各 pipeline 分支，每个分支独立流光。
- 加一个 pipeline → 右侧分叉动画多长出一条分支（scale-in）。
- 条件树节点增删动画。

**📋 复制这段给 AI：**

```
你是资深前端工程师 + 动效设计师。生成「Subscription 编辑页」纯静态 HTML demo（单文件）。左右分栏：左表单，右实时分叉管道预览。这是配置页里最有看点的一页。

技术：CDN Tailwind + 纯 CSS/SVG。深色科技感，玻璃卡片，青紫流光。

布局（左右）：

左栏（表单，~55%）：
1. Pipeline 绑定区（重点）：多选器，mock 已选 [高额订单告警] [风控告警] [+ 添加]。选中的 pipeline 显示为可删除胶囊，可加可删。这体现「一个 subscription 绑多个 pipeline」。
2. 源配置区：sourceType tab（CDC/CRON/API），选中后显示对应字段表单（mock CDC：mq、topic、db、table、opTypes 多选）。表单控件要好看（玻璃输入框）。
3. 条件编辑器（重点）：可视化 Condition 树。顶部一个 AND/OR 容器节点，下挂叶子节点（Compare：字段 op 值，如 amount GT 10000；In：字段 in [v1,v2]）。节点可折叠，旁边 + 号可加子节点。mock 一棵两层树（AND 下两个 Compare 叶子）。
4. actionType 选择：RUN/SCHEDULE/CANCEL 单选胶囊。

右栏（分叉管道预览，~45%）：
- 一张「配置预览」大卡，内是 SVG 分叉管道：
  - 左侧 Source 节点（按左栏选的 sourceType 显示图标 + 摘要）。
  - 主干流光到中间 Subscription 节点（显示条件摘要「amount > 10000」）。
  - Subscription 节点向右分叉出多条分支，每条分支末端一个 Pipeline 节点（数量 = 左栏 pipelineIds 数量，mock 3 条：高额订单告警 / 风控告警 / 库存告警），每个 Pipeline 末端一个小 Alert 图标。
  - 每条分支独立流光（青紫渐变路径 + 粒子流动）。
- 当左栏「添加 pipeline」时，右栏分叉动画多长出一条分支（scale-in + 路径绘制动画）—— 用一个「+ 添加演示分支」按钮模拟这个交互。
- 每个节点 hover 放大 + 点击展开右侧小抽屉显示该节点配置摘要。

数据 mock 写 script，字段照抄：pipelineIds(数组)/sourceType(CDC,CRON,API)/源字段/fieldFilter(Condition 树)/actionType(RUN,SCHEDULE,CANCEL)。注释标 // POST /api/v1/namespaces/{ns}/subscriptions。
输出完整 HTML。分叉管道流光是这页的灵魂，要流畅、能体现「一绑多」。
```

---

## 5. 全局骨架 / 导航

**页面目标**：把以上页面串成一个完整 demo，顶部统一 namespace 切换 + 角色页签，左侧或顶部导航在页面间跳转。

**接口字段对齐**：`GET /api/v1/namespaces` → namespace 列表（mock：ops / payment / risk）。

**布局结构**：
- 顶部导航：左 logo、中 namespace 切换器（下拉，全局生效）、右 角色页签（配置 / 告警 / 大盘）。
- 角色页签切换主导航分组：
  - 大盘：C1 / C2
  - 告警：B1 / B2 / B3 / B4
  - 配置：A1 / A2 / A3 / A4 / A5
- 页面切换有淡入过渡。

**招牌动效**：namespace 切换时，全局"刷新"一次流光（顶部一条横线扫过）；角色页签切换有滑动指示条。

**📋 复制这段给 AI：**

```
你是资深前端工程师。生成一个「全局导航骨架」纯静态 HTML demo（单文件），用于把多个页面串成一个完整可演示的 demo。

技术：CDN Tailwind + 纯 CSS。深色科技感，顶部玻璃导航条。

布局：
1. 顶部固定导航条（玻璃质感，blur）：
   - 左：logo「A-Solid Observe」+ 一个小的流光管道图标。
   - 中：namespace 切换器（下拉，mock 值 ops / payment / risk，当前 ops）。切换时，导航下方一条青紫流光横线从左扫到右一次（全局刷新动画）。
   - 右：角色页签三组（大盘 / 告警 / 配置），当前激活的页签下方有滑动指示条（流光下划线随点击滑动）。页签上带小角色图标。
2. 角色页签决定下方主导航：
   - 选「大盘」→ 次级导航：C1 首页 / C2 演示。
   - 选「告警」→ B1 列表 / B3 执行历史 / B4 失败执行。
   - 选「配置」→ A1 Pipeline / A4 Subscription。
3. 主内容区：放一个占位的「页面内容区」玻璃卡片，标题写当前选中页（如「大盘 · 首页」），内容写「（此处嵌入对应页面 demo）」。点击次级导航切换时，主内容区淡入过渡。
4. 目的：作为 demo 容器，演示时点导航切换页面。所有导航项的链接/mock 都就绪。

namespace 列表 mock 写 script（ops/payment/risk）。注释标 // GET /api/v1/namespaces。
输出完整 HTML，重点是导航切换的丝滑过渡和角色分组清晰。
```

---

## 6. 使用建议（给用这份文档的人）

1. **首次生成**：建议先做 **C2 Demo 页**（招牌）+ **C1 大盘**，这两个最能对外展示。把第 1 节全局设计语言 + 对应页提示词一起喂给 AI。
2. **保持一致性**：之后每生成一个新页，把第 1 节再喂一次（或要求 AI"沿用之前 C1 的视觉风格"），避免各页风格分裂。
3. **串联演示**：最后用第 5 节全局骨架把所有页面串起来，作为一个完整 demo 投屏/录屏。
4. **字段别改**：提示词里的字段名/枚举/路径都是对齐真实接口的，AI 生成时不要让它"自由发挥"改成别的命名，否则后续接真实后端要返工。
5. **动效优先级**：如果 AI 一次做不完所有动效，让它优先保证招牌动效（管道流光、count-up、severity 光晕、监护仪诊断图）。

---

## 7. 待决策项（沿用页面规划原文档）

1. 真实后端接口补齐顺序（见 `2026-07-19-controlplane-api-audit.md` §6 B5 批次）—— demo 不依赖，但接真实数据时需要。
2. 告警 ack/resolve 是否纳入一期（依赖 ADR-0005）。
3. 跨 namespace 汇总（C3）是否开放（与 ADR-0002 冲突）—— 本次未为此页生成提示词。
4. 实时推送是否需要（demo 用轮询/setInterval 模拟，真实环境一期轮询够用）。
