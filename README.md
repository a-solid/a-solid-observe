# a-solid-observe

部门内多团队共用的观测/监测平台：监听 CDC MQ / 定时任务 / HTTP API，执行 Groovy 脚本检测逻辑，命中规则时落库告警 + 证据，告警通过 HTTP API 暴露给 Grafana / AlertManager 拉取。

## 状态

P0（脚手架）。完整设计与阶段路线见：

- [`docs/observe-platform-design.md`](docs/observe-platform-design.md) — 一期设计（当前已实现 / 一期范围内置）
- [`docs/observe-roadmap.md`](docs/observe-roadmap.md) — 远期 / 二期项 / 实施阶段
- [`AGENTS.md`](AGENTS.md) — AI 协作规范
- [`docs/superpowers/plans/2026-07-11-observe-p0-scaffold-plan.md`](docs/superpowers/plans/2026-07-11-observe-p0-scaffold-plan.md) — P0 plan

## 构建

要求 JDK 17+、Maven 3.8.5+。

```bash
mvn clean install          # 编译所有 module
mvn spotless:apply         # 自动格式化
mvn spotless:check         # 验证格式
mvn checkstyle:check       # 验证 Checkstyle 规则
mvn dependency:tree        # 查看 module 依赖关系
```

## 模块结构

| Module | 职责 |
|---|---|
| `observe-kernel` | 共享内核：Event、ExecutionContext、GroovyScriptEngine、UDF、Telemetry、异常体系 |
| `observe-pipeline` | 核心 domain + execution engine：Pipeline、Node、Source 适配器、AlertSink |
| `observe-alerting` | 告警领域：AlertEntity、Evidence、查询服务、Grafana datasource 契约 |
| `observe-config` | 配置领域：PipelineDefinition、PipelineVersion、Subscription、Threshold、热加载 |
| `observe-controlplane` | Web API + BFF：REST controller、DTO、validator |
| `observe-bootstrap` | main 入口 + 装配（worker / controlplane / all-in-one） |

依赖方向：`controlplane → config → pipeline → kernel`；`alerting → kernel`；`bootstrap` 装配所有。详见 [`AGENTS.md` §3](AGENTS.md)。

## 代码风格

- Spotless + `palantir-java-format` 自动 format：`mvn spotless:apply`
- Checkstyle 兜底规则（方法 ≤ 50 行、文件 ≤ 500 行、不允许 `*` import 等）：`build/checkstyle/checkstyle.xml`
- 提交前确保 `mvn spotless:check checkstyle:check` 通过

详见 [`AGENTS.md` §5](AGENTS.md)。
