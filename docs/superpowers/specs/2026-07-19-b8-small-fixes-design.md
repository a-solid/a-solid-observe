# B8 小修小补设计

**日期**：2026-07-19
**状态**：Proposed
**批次**：B8（零散设计发现项清理）
**前置依赖**：**代码上**独立于 B5/B6/B7（不改信封/查询/告警生命周期）。唯一耦合是 **Flyway 迁移版本号**：B7 占 alerting `V2`、B8 的 `drop generator_url` 占 alerting `V3`、B8 的 `trigger_type` CHECK 占 pipeline `V2`。若 B8 先于 B7 实现，版本号按实际顺序重排（实现时定号）。
**关联**：`docs/2026-07-19-design-and-data-model-review.md` §4（发现项来源）

---

## 1. 背景与目标

设计 review（§4）发现 4 个零散问题，各自小、彼此无关，合在一个轻量批次清理：
1. `EvidencePo.size_bytes` 命名误导（存的是 Map entry 数，非字节数）。
2. `trigger_type` 写裸字符串 `"UNKNOWN"` 绕过 `SourceType` 枚举，且 DB 无 CHECK 约束该列。
3. `Source.type()` 接口方法 B3 后已无生产调用（源类型改由 Event 子类型决定）。
4. `AlertEntity.generatorURL` + DB 列 `generator_url` 生产中永远 null，从未写入非 null。

**目标**：逐一修正，消除「名实不符」与「逃逸点」，不留半成品。每项独立、可单独回滚。

**非目标**：不做更大重构；不顺手改无关代码。

---

## 2. 逐项设计

### 2.1 `size_bytes` 命名误导
- **现状**：`EvidenceMapper.java:43` 把 `entity.outputs().size()`（Java `Map` entry 数）赋给 `po.sizeBytes`；列名 `size_bytes`（`V1__init.sql`）。`DefaultAlertSink` 后续可能覆盖为真实字节，但 mapper 的初始赋值语义错。
- **方案**：mapper 改为计算真实序列化字节数（`objectMapper.writeValueAsBytes(outputs).length`），让 `size_bytes` 名实相符。
- **实现**：`EvidenceMapper.toPo` 接收 `ObjectMapper`（或在 mapper 内用 `JsonUtil`），`sizeBytes = outputs==null?0:objectMapper.writeValueAsBytes(outputs).length`。
- **不改列名**（避免迁移）；只修正取值语义。若 review 后更想改列名为 `output_field_count` 则另议——本 spec 选「修正取值」。
- **测试**：`EvidenceMapperTest` 断言 `sizeBytes` ≈ 真实 JSON 字节数（非 entry 数）。

### 2.2 `trigger_type` 写 `"UNKNOWN"` 绕过枚举
- **现状**：`JpaExecutionRecorder.java:64,97` 在 `SourceType` 为 null 时写字符串 `"UNKNOWN"`，但 `SourceType` 枚举无 `UNKNOWN` 常量，`executions.trigger_type`/`failed_executions.trigger_type` 也无 CHECK 约束——裸字符串逃逸点。
- **方案（二选一，spec 选 A）**：
  - **A（推荐）**：给 `SourceType` 加 `UNKNOWN` 常量；recorder 写 `SourceType.UNKNOWN.name()`；V2 迁移给两列加 `CHECK (trigger_type IN ('CDC','CRON','API','UNKNOWN'))`。枚举与 DB 对齐，无逃逸。
  - B：保证 `SourceType` 写入前非 null（ExecutionMeta 不允许 null triggerType），删掉 UNKNOWN 分支。改动面更大，且 ExecutionMeta 构造点可能传 null，风险高。
- **实现**：
  - `observe-kernel/.../event/model/SourceType.java` 加 `UNKNOWN`。
  - `JpaExecutionRecorder` 两处 `null ? "UNKNOWN" : type.name()` 改为 `(type!=null?type:SourceType.UNKNOWN).name()`。
  - `observe-pipeline/src/main/resources/db/migration/pipeline/V2__trigger_type_check.sql` 加 CHECK（两表）。
- **注意**：`ExecutionDto.triggerType` 是 `String`，前端按枚举值显示；`UNKNOWN` 作为兜底值文档化。

### 2.3 删除 `Source.type()`
- **现状**：`Source.java:7` 接口方法 `SourceType type()`，3 个实现者（`InMemoryCdcSource`/`IbmMqCdcSource`/`ApiSource`）各返回常量；**零生产调用**（B3 后源类型由 Event 子类型决定，`WorkerShutdown` 只调 `.stop()`）。
- **方案**：删接口方法 + 3 个实现的 `@Override type()`。API 破坏性变更，但无外部消费者（内部接口）。
- **验证**：删后 `mvn compile` 必须绿（若有隐藏调用会编译失败，自然拦截）。
- **测试**：无（删除不可达方法不改行为）。

### 2.4 删除 `AlertEntity.generatorURL` + 列
- **现状**：`AlertEntity.generatorURL`（domain）、`AlertPo.generatorUrl` + 列 `generator_url`（V1 SQL）生产永远 null，`DefaultAlertSink.buildAlertEntity` 硬编码 null，无写入非 null 的路径。
- **方案**：删 domain 字段 + PO 字段 + mapper 拷贝 + V2 迁移 drop column。
- **实现**：
  - `AlertEntity` 删 `generatorURL`（record 字段，影响构造调用点 `DefaultAlertSink.buildAlertEntity`、`AlertMapper.toEntity`——同步删）。
  - `AlertPo` 删 `generatorUrl` 字段。
  - `AlertMapper` 删对应拷贝。
  - `AlertDto` 若有该字段一并删（检查）。
  - `observe-alerting/src/main/resources/db/migration/alerting/V3__drop_generator_url.sql`（与 B7 的 V2 同模块，序号递增）：`ALTER TABLE alerts DROP COLUMN generator_url;`
- **与 B7 的迁移序号协调**：B7 用 alerting V2，本项用 alerting V3。若 B8 先于 B7 实现，则 B8 占 V2、B7 顺延——实现时按实际顺序定号。

---

## 3. 验收标准（每项独立）

1. `mvn compile` + `mvn test` + `mvn checkstyle:check` 全绿
2. **2.1**：`EvidenceMapperTest` 断言 sizeBytes 为真实字节数
3. **2.2**：`SourceType.UNKNOWN` 存在；V2 CHECK 生效（插非法 trigger_type 失败）
4. **2.3**：`Source` 接口无 `type()`，编译绿
5. **2.4**：`alerts` 表无 `generator_url` 列；`AlertEntity`/`AlertPo`/`AlertDto` 无该字段；端到端 emit 告警正常

---

## 4. 风险

| 风险 | 缓解 |
|---|---|
| `generatorURL` 是 record 字段，删影响构造调用点 | 全量搜构造点同步改；编译拦截 |
| `Source.type()` 有隐藏反射调用 | grep 确认无；删后编译绿即证 |
| 迁移序号与 B7 冲突 | 实现时按批次实际顺序定号 |
| `UNKNOWN` 进枚举后 DryRunService 字符串 `"FAILED"` 无关 | 不影响（不同枚举） |

---

## 5. 实现顺序建议

B8 内部顺序（无强依赖，按风险递增）：
1. 2.3 删 `Source.type()`（最纯，编译即验）
2. 2.1 `size_bytes` 取值修正
3. 2.2 `trigger_type` UNKNOWN + CHECK
4. 2.4 删 `generatorURL`（涉及迁移 + 多文件 record 构造）

---

## 附录：改动文件清单

**2.1**：`EvidenceMapper.java`（+ObjectMapper 注入）、`EvidenceMapperTest.java`（新建/扩展）
**2.2**：`SourceType.java`（+UNKNOWN）、`JpaExecutionRecorder.java`、`pipeline/V2__trigger_type_check.sql`（新建）
**2.3**：`Source.java`、`InMemoryCdcSource.java`、`IbmMqCdcSource.java`、`ApiSource.java`
**2.4**：`AlertEntity.java`、`AlertPo.java`、`AlertMapper.java`、`AlertDto.java`（若有）、`DefaultAlertSink.java`（构造点）、`alerting/V3__drop_generator_url.sql`（新建）
