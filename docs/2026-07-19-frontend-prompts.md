# 前端页面功能描述（静态 Demo）

按页面分段，每页一段功能 + 期望描述，用于交给 AI 生成静态 demo 页面。字段名/枚举/接口路径需照抄（对齐真实后端）。

视觉风格、配色、字体、组件库、明暗、动效实现方式不在此规定，由设计侧发挥。倾向浅色为主、现代数据产品气质，不要像传统运维后台；各类状态/严重度一眼可辨且不只靠颜色。

贯穿主线：数据像流体在一条溯源链路中流动 —— `Source → Subscription → Pipeline → Alert`。95% 的 pipeline 只有一个节点，所以重点是这条链路本身，而非 pipeline 内部多节点。

---

## 全局骨架

顶部导航：左 logo，中 namespace 切换器（mock：ops / payment / risk），右 角色页签（大盘 / 告警 / 配置）。角色页签决定次级导航分组，页面切换有过渡，namespace 切换有一次全局反馈。当前所在位置明确。
接口：`GET /api/v1/namespaces`。

---

## C1 大盘首页

对外展示核心价值的英雄页，给 Manager 看。一眼看清今天监测了多少、抓到多少问题、成功率多高。

顶部 hero 区可视化溯源链路 Source→Subscription→Pipeline→Alert，叠加"今日 N 个事件流经、M 个触发告警"实时计数。4 张 KPI 卡片：今日告警总数 / FIRING / CRITICAL / 执行成功率，FIRING、CRITICAL 卡片更值得关注。趋势区：告警趋势按小时按 severity 堆叠 + 执行吞吐按小时 success/failed。分布区：告警按 severity、按 team。底部 Top5：最活跃 pipeline、最频繁告警 fingerprint。趋势要有"实时"感（定时器加新点），KPI 数字有进入动效，hero 链路有流动感。

字段：`bySeverity{CRITICAL,WARNING,INFO}`、`byStatus{FIRING,RESOLVED}`、`total`、timeseries `[{bucketStart,count}]`、`successRate`。
接口：`GET /api/v1/stats/alerts`、`/stats/alerts/timeseries`、`/stats/executions`。

---

## C2 对外演示页（招牌）

产品的招牌 demo，可视化一个事件如何从 Source 注入，经过 Subscription 过滤、Pipeline 判定，最终产出一条 Alert。适合投屏录屏。

中央舞台展示 4 节点链路：Source「CDC 事件」→ Subscription「orders 表 · 金额>10000」→ Pipeline「check: 高额订单告警」→ Alert。底部「▶ 演示模式」+ 自动循环。播放流程约 3 秒：事件从 Source 注入 → 流向 Subscription 时 2-3 个不满足条件的事件被剔除、主事件通过 → Pipeline 判定 → 末端产出告警卡（severity CRITICAL、fingerprint、labels.entity「orders」、annotations「金额 ¥58,200 超阈值」、startsAt）。节点高亮跟随事件位置，每个节点可点击展开详情。动效要讲清"事件→告警"的因果叙事，流畅但不浮夸。

字段：告警卡 `severity / fingerprint / labels.entity / annotations / startsAt`。
接口：`POST /api/v1/events` → eventId；`GET /api/v1/alerts/{id}`。

---

## B1 告警列表页

值班首屏，一眼看到"现在最严重的问题"。最严重的告警抢眼且置顶，新告警能被注意到，不做密密麻麻的运维日志表格。

筛选条：severity 多选（CRITICAL/WARNING/INFO）、status（FIRING/RESOLVED/全部）、team、pipeline、时间范围，控件要好看不是原生堆砌。severity 分组概览：CRITICAL 12 / WARNING 47 / INFO 5，可点击筛选。主区告警列表（卡片或富列表，非死板表格），CRITICAL+FIRING 置顶且抢眼但不刺眼，RESOLVED 弱化。每条含 severity 标识、fingerprint、labels.entity、相对时间、dedupCount、team、所属 pipeline。顶部"实时/已连接"指示，新告警定时进入制造实时感。

字段：`severity / status / fingerprint / labels.entity / startsAt / lastSeenAt / dedupCount / team / pipelineId`。
接口：`GET /api/v1/alerts?namespace=&status=&team=&pipeline_id=&severity=&from=&to=&limit=`。

---

## B2 告警详情页

排障核心页，顶部用溯源链路清楚展示这条告警"从哪来"。

顶部溯源链路条（横向 4 节点）：Source「CDC: orders 表 INSERT」→ Subscription「高额订单监控」→ Pipeline「check 高额订单告警 v3」→ Alert「本告警」，每个节点带图标和名称可点击，加载时有一次点亮/流转的进入感。告警元信息卡：severity 徽标（本条 CRITICAL）、fingerprint、labels、annotations，配时间线 startsAt→lastSeenAt→endsAt/resolvedAt。证据区：Pipeline 节点输出 evidence，提供 JSON 视图（语法高亮，mock `{matched:true, value:58200, threshold:10000}`）和表格视图切换。状态流转时间线（垂直）：FIRING 首次 → dedup ×3 → ack by alice → RESOLVED。底部固定操作条：Acknowledge / Resolve / Silence 1h，Resolve 是关键动作要恰当强调，点击给反馈。溯源链路是这页视觉重点。

字段：`severity / labels / annotations / startsAt / lastSeenAt / endsAt / resolvedAt / status / dedupCount / fingerprint`；evidence 为节点输出对象。
接口：`GET /api/v1/alerts/{id}`、`/alerts/{id}/evidence`；写操作 `POST /alerts/{id}/ack`、`/resolve`。

---

## B3 执行历史页

看一段时间内 pipeline 跑了多少次、结果如何、每次耗时，体现"执行流水"感而非静态报表。

筛选条：pipeline、status 多选（SUCCESS/SHORT_CIRCUITED）、时间范围；统计概览：SUCCESS 数、SHORT_CIRCUITED 数、平均耗时。主区执行列表（每条一行，mock 10-15 条，按时间倒序）：时间戳 + triggerType 标识（CDC/CRON/API 图标区分）、耗时可视化（宽度 ∝ durationMs，按结果着色，标注时长）、结果徽标，SHORT_CIRCUITED 与 SUCCESS 可区分。点击展开 triggerEvent 摘要。新执行定时进入制造流水感。

字段：`triggerType / triggerEvent / status(SUCCESS,SHORT_CIRCUITED) / durationMs / startedAt / pipelineId`。
接口：`GET /api/v1/executions?namespace=&pipeline_id=&status=&from=&to=&limit=`。

---

## B4 失败执行页

定位失败"病灶"。因 95% pipeline 单节点，失败基本是单节点问题，要让人秒懂"在哪一步、因为什么崩了"。

左栏失败列表：errorType 分组筛选 + 失败卡片（errorType、nodeName、pipeline、status、createdAt），PENDING 比 RESOLVED/IGNORED 更醒目以吸引处理，选中态高亮。右栏诊断面板（选中后）：标题「节点诊断 · {nodeName}」+ errorType + 一键重试按钮。核心诊断可视化展示「输入 → 节点处理 → 输出」，清晰标注失败发生在哪一步（位置随 errorType，如 NPE 标在 check 求值段、EmitError 标在输出段），配标签如「NPE @ check」。诊断形态由设计决定。下方 errorMessage（突出）+ 可展开 stackTrace。选中不同失败时诊断面板重新展示一次。

字段：`errorType / nodeName / errorMessage / stackTrace / status(PENDING,RESOLVED,IGNORED) / createdAt / pipelineId`。
接口：`GET /api/v1/failed-executions?namespace=&pipeline_id=&limit=`；`POST /failed-executions/{id}/retry`。

---

## A1 Pipeline 列表页

维护者看有哪些 pipeline、状态如何，卡片网格有产品感不像 CRUD。

标题 + 「+ 新建 Pipeline」。筛选条：status（DRAFT/PUBLISHED/ARCHIVED）、team、application、搜索。卡片网格（响应式，mock 9 张）：name + 描述、状态指示、team/application 标签、currentVersion、updatedAt、近期执行小趋势图。卡片操作：查看 / 编辑 / 归档。状态分布有差异：大部分 PUBLISHED、2 张 DRAFT、1 张 ARCHIVED 弱化。

字段：`name / team / application / status(DRAFT,PUBLISHED,ARCHIVED) / currentVersion / updatedAt / labels`。
接口：`GET /api/v1/namespaces/{ns}/pipelines`。

---

## A2 Pipeline 编辑器 + 干跑面板

左右分栏：左编辑 pipeline，右校验 + 干跑，干跑结果讲清"输入→处理→输出→是否告警"的因果。

顶部工具条：pipeline 名「高额订单告警」+ 版本「draft v4」+ 校验 / 干跑 / 保存版本 / 发布（主操作）。左栏编辑区 tab「可视化」「JSON」：可视化显示单节点卡片（类型 check，可配 threshold、compare），JSON 视图语法高亮。右栏：校验结果（✓ 通过或错误指明位置）+ 干跑卡（选示例事件，展示输入→节点处理→输出；matched=true 则触发告警 CRITICAL + 节点输出 `{matched:true, value:58200, threshold:10000}`，matched=false 则未触发）。底部版本操作：保存为新版本 / 发布 / 查看历史 + 版本时间线缩略。

接口：`POST /api/v1/validate/pipeline`、`/validate/dry-run`；版本 `POST .../versions`、`.../versions/{v}/publish`。

---

## A3 Pipeline 版本管理页

看一个 pipeline 的所有版本、发布历史、版本 diff、一键回滚。

左栏版本时间线（垂直，mock 5 版）：每版含版本号、status（PUBLISHED/ARCHIVED/DRAFT）、publishedBy、时间，当前发布版高亮标记 current，可勾选两版做 diff。右栏：diff 标题「v3 → v4 变更」+ 双栏 diff（左旧右新行对齐，删除行/新增行高亮，mock 如 threshold 10000→20000）+ 底部按钮「回滚到 v3 / 发布 v4 / 归档 v3」，回滚是关键有风险操作要恰当强调和确认感。

字段：`version / status / definitionHash / publishedBy / 时间`。
接口：`GET .../pipelines/{name}/versions`、`.../versions/{v1}/diff/{v2}`；`POST .../versions/{v}/publish`、`/archive`。

---

## A4 Subscription 列表页

看有哪些订阅、什么源、绑了哪些 pipeline。重点体现 `pipelineIds:[a,b,c]`：一个订阅可绑多个 pipeline。

sourceType 分组（CDC/CRON/API，带数量）。卡片网格（mock 8 张）：sourceType 标识（图标区分）+ name、源配置摘要（CDC 显示 mq+topic+table+opTypes；CRON 显示 cron 表达式如 `*/5 * * * *`；API 显示 apiName）、**绑定 Pipeline 区清晰展示 pipelineIds 数组**（单个一个胶囊，多个清楚展示全部或带 +N，务必有几张绑 2-3 个 pipeline 的卡）、actionType（RUN/SCHEDULE/CANCEL）+ status。

字段：`name / sourceType(CDC,CRON,API) / pipelineIds(数组) / actionType(RUN,SCHEDULE,CANCEL) / cronExpression / status`。
接口：`GET /api/v1/namespaces/{ns}/subscriptions`。

---

## A5 Subscription 编辑页

左右分栏：左表单，右实时分叉链路预览。配置页里最有看点的一页。

左栏表单：① Pipeline 绑定区（多选器，mock 已选 [高额订单告警][风控告警][+添加]，支持一绑多可加可删）；② 源配置（sourceType tab CDC/CRON/API，CDC 字段 mq/topic/db/table/opTypes）；③ 条件编辑器（可视化 Condition 树：AND/OR 容器 + Compare 叶子如 amount GT 10000 + In 叶子，可增删，mock 一棵两层树）；④ actionType（RUN/SCHEDULE/CANCEL）。右栏分叉链路预览：Source → Subscription（显示条件摘要「amount > 10000」）→ 分叉到多个 Pipeline 节点（数量 = 左侧 pipelineIds，mock 3 条：高额订单告警/风控告警/库存告警），每个末端告警产出标识。加/减 pipeline 时右侧分叉实时变化（有进入感），节点可点击展开配置摘要。分叉链路预览是这页灵魂，让人秒懂"一个源的事件分发给哪几个 pipeline"。

字段：`pipelineIds(数组) / sourceType(CDC,CRON,API) / 源字段 / fieldFilter(Condition 树) / actionType(RUN,SCHEDULE,CANCEL)`。
接口：`POST/PUT /api/v1/namespaces/{ns}/subscriptions`。
