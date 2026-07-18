# 去掉 pipelineLabels denormalize，alerts 用 label 投影列

## Status

accepted

## Context

历史设计：`alerts.pipeline_labels` 是从 pipeline 拷贝的 denormalize 副本（历史保真），与 `alerts.labels`（Grafana 路由用）形成"两层 labels"。同时 `pipelines.labels` 是无索引 JSON，按 label 查 pipeline/告警走全表扫。Sybase ASE 无 JSON 索引能力。

## Decision

1. **去掉 `alerts.pipeline_labels` 列**。emit alert 时，把对路由/分类有意义的 label（含原 team/app/line）固化进 alert 自己的 `alerts.labels`。alert 独立携带，不再 denormalize pipeline 副本，也不再区分"两层 labels"。

2. **alerts 表加 label 投影列** `label_app` / `label_team` / `label_line`（普通 B-tree 索引），从 `alerts.labels` JSON 投影，应用层 emit 时同步填充。支撑按 app/team/line 高频过滤告警。

3. **pipelines/subscriptions 的 labels 保留 JSON，一期不加索引**。配置页 label 查询走全表扫（低频可接受）。

4. **namespace 仍是主表一等列**（隔离铁律，不进 label 投影）。

## Why

- 核心搜索场景是告警面板查 alerts，把索引预算集中投在 alerts 上性价比最高。
- Sybase 无 JSON 索引，固定维度物化列是唯一可行方案；SQL Server（计算列+索引）/ MySQL（函数索引）未来切库时不破坏（可改为 DB 自动同步投影）。
- 去 denormalize 副本降低写入复杂度和一致性维护成本；alert.labels 自携带让 alert 与 pipeline 解耦（pipeline 删/改不影响历史 alert 分类）。

## Consequences

- `team`/`application` 作为一等领域字段消失，统一为 label；`label_team`/`label_app`/`label_line` 是 label 的索引投影，非独立领域概念。
- 新增 label 查询维度 = alerts 加投影列 + 回填；label JSON 数据不变。
- label_value 统一字符串化，label 只支持等值/IN 过滤；范围查询走独立列（severity/status/started_at），不走 label。
- evidence 关联的 label 历史由 alert.labels 自携带保证（与改造3 的 1:N evidence 设计衔接）。

## Considered Options

- **EAV 键值索引表（resource_labels）**：rejected for v1。任意维度索引能力强，但写入双份 + 查询 JOIN，一期过度设计。保留为二期演进项（若维度爆炸再上）。
- **三表都加投影列**：rejected。pipelines/subscriptions label 查询低频，不值得三表写入同步成本。
- **保留 pipelineLabels denormalize**：rejected。两层 labels 认知负担大，且 denormalize 副本一致性维护贵。
