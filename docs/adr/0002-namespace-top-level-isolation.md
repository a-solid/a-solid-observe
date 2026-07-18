# 引入 Namespace 做顶层隔离

## Status

accepted

## Context

一期后多团队共用单个 observe 实例时：(1) pipeline/subscription 名字全局唯一会冲突；(2) 团队间资源互不可见没有强制边界。现有 `team`/`application` 字段只是字符串软标签，query 不带过滤照样能查到别人的数据，做不到 (a) 名字作用域和 (b) 可见性边界。

## Decision

引入 **namespace** 作为顶层隔离维度，承担两个职责：(a) 名字作用域（同 namespace 内资源名唯一，跨 namespace 可重名）；(b) 可见性/RBAC 边界（未来 RBAC 的挂载点）。namespace 是显式资源（`namespaces` 表，CRUD + metadata）。

`team`/`application` 字段从顶层隔离职责中移除，**降级为 pipeline 的 label**（按维度筛选用），不再作为隔离边界。

**隔离强度 = 软隔离**：应用层所有读写必带 namespace 过滤，DB 无强制约束。漏带过滤即串数据 bug（铁律，code review 必查）。

环境隔离（prod/uat）一期**不**用 namespace 承担——靠 Spring profile / 多实例部署，namespace 只管资源隔离。

## Why

- K8S namespace 模型成熟、团队认知成本低。
- team/application 本就是字符串软标签（§1.4 "不引入团队表"），升级为隔离边界不划算；用 namespace 顶上、team/app 降为 label，职责更清。
- 软隔离符合项目"不用 FK、应用层保证完整性"一贯风格；Sybase 不支持行级安全，硬隔离不可行。

## Consequences

- 几乎所有资源表加 `namespace` 列；PK 见 ADR-0003。
- alerts/executions/failed_executions denormalize namespace（从触发 pipeline 继承，历史保真）。
- 所有 query/API 必带 namespace 过滤——这是新的应用层铁律，需在 review checklist 固化。
- 跨 namespace 的运维操作（全局看板、跨 ns 告警聚合）需要显式的"跨 namespace"权限，一期不做。
