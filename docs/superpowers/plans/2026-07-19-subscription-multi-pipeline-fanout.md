# Subscription 多 Pipeline 扇出 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让一个 subscription 可绑定多个 pipeline（`pipelineIds` 列表），同源事件扇出到所有绑定 pipeline，各自独立执行/失败隔离。

**Architecture:** 配置态 `SubscriptionDefinition` 与 `subscriptions` 表把单 `pipeline_id`/`pipeline_version` 列换成 JSON 数组列 `pipeline_ids`；运行态 `Subscription` 同步；matcher 的 `MatchedSubscription` 从 `(sub, pipeline)` 改为 `(sub, List<Pipeline>)`；`SourceDispatcher` 内层 for 扇出，每个 (sub,pipeline) 独立提交 runnerPool。版本不再绑定（跟 currentVersion）。

**Tech Stack:** Java 21 (records/sealed), Spring Boot 4.1, JPA/Hibernate, H2 (test) / Sybase ASE (prod 参考), JUnit 5 + AssertJ, MyBatis 无（JPA），Checkstyle（行长 ≤ 120）。

## Global Constraints

- 不动 `action_type`/`schedule_delay_ms`/`schedule_correlation_key_path` 列与 `Action` sealed interface / `DelayedActionHandler` —— 这些归延时 spec（`2026-07-19-subscription-delayed-redesign.md`）。
- 软隔离铁律（ADR-0002）：`pipelineIds` 校验每个 id 同 namespace 且存在。
- snowflake id（ADR-0003）：`pipeline_ids` 存 BIGINT id 的 JSON 数组。
- Checkstyle 行长上限 120，方法/类 Javadoc 与现有风格一致（中文注释）。
- DDL 单一化：只维护 `observe-config/src/main/resources/db/migration/config/V1__init.sql`，不新增 V2 迁移（项目惯例，H2 由 ddl-auto 驱动）。
- 每个任务结束 commit；commit message 末尾加 `Co-Authored-By: Claude <noreply@anthropic.com>`。

## File Structure

| 文件 | 责任 | 改动 |
|---|---|---|
| `observe-config/.../db/migration/config/V1__init.sql` | `subscriptions` DDL | 删 `pipeline_id`/`pipeline_version` 列 + `ck_sub_schedule` 里对 `pipeline_id` 的引用；加 `pipeline_ids` 列 |
| `observe-config/.../domain/SubscriptionDefinition.java` | 配置态 record | 删 `pipelineId`/`pipelineVersion`，加 `List<Long> pipelineIds` |
| `observe-config/.../infrastructure/persistence/SubscriptionPo.java` | JPA PO | 删 `pipelineId`/`pipelineVersion`，加 `pipelineIds`（JSON 转换器） |
| `observe-config/.../infrastructure/persistence/SubscriptionMapper.java` | PO↔entity | 同步映射 + JSON 编解码 |
| `observe-config/.../infrastructure/persistence/LongListToJsonConverter.java` | JPA 转换器 | 新建：`List<Long>` ↔ JSON 字符串 |
| `observe-config/.../application/SubscriptionCrudService.java` | CRUD + 校验 | `validatePipeline` 改为校验 `pipelineIds` 列表 |
| `observe-controlplane/.../interfaces/SubscriptionController.java` | REST | `SubscriptionFields` 改 `pipelineIds` |
| `observe-controlplane/.../interfaces/dto/SubscriptionDto.java` | DTO | 删 `pipelineId`/`pipelineVersion`，加 `pipelineIds` |
| `observe-pipeline/.../domain/subscription/Subscription.java` | 运行态 record | 删 `pipelineId`/`pipelineVersion`，加 `List<Long> pipelineIds`（保留 `action`） |
| `observe-pipeline/.../application/SubscriptionMatcher.java` | 匹配接口 | `MatchedSubscription` 第二字段 `Pipeline` → `List<Pipeline>` |
| `observe-pipeline/.../infrastructure/subscription/DefaultSubscriptionMatcher.java` | 匹配实现 | `tryMatch` 返回 `List<Pipeline>` |
| `observe-pipeline/.../application/SourceDispatcher.java` | 分发 | `dispatch` 内层 for 扇出；`submitMatched` 签名改 `(subscription, pipeline, event)` |
| `observe-config/.../application/PipelineRegistryLoader.java` | 配置→运行态加载 | `pipelineIds` 解析 + 至少一个存在才纳入 |

测试文件：
- `observe-config/src/test/.../SubscriptionCrudServiceTest.java`（已存在，改断言）
- `observe-pipeline/src/test/.../DefaultSubscriptionMatcherTest.java`（已存在，改构造 + 加多 pipeline 用例）
- `observe-pipeline/src/test/.../SourceDispatcherTest.java`（已存在，验证扇出）
- `observe-bootstrap/src/test/.../EndToEndFlowTest.java`（回归）

---

### Task 1: DDL — `subscriptions` 表 pipeline 列重构

**Files:**
- Modify: `observe-config/src/main/resources/db/migration/config/V1__init.sql`（`subscriptions` 表段）

**Interfaces:**
- Produces: `subscriptions.pipeline_ids VARCHAR(4096) NOT NULL` 列；删除 `pipeline_id`/`pipeline_version` 列。

- [ ] **Step 1: 修改 `subscriptions` 表定义**

在 `V1__init.sql` 的 `CREATE TABLE subscriptions (...)` 块里：

把
```sql
    pipeline_id BIGINT NOT NULL,
    pipeline_version INT NOT NULL,
```
删除，并在 `namespace VARCHAR NOT NULL,` 下一行加：
```sql
    pipeline_ids VARCHAR(4096) NOT NULL,
```

同时 `ck_sub_schedule` 约束**不引用 pipeline_id/pipeline_version**（当前它只引用 action_type/schedule_* 字段，确认无需改）。检查全文确认没有其它索引/约束引用 `pipeline_id` 或 `pipeline_version`（如 `idx_sub_source` 用的是 `db, table_name, status`，不受影响）。

- [ ] **Step 2: 验证 SQL 语法**

Run: `grep -n "pipeline_id\|pipeline_version\|pipeline_ids" observe-config/src/main/resources/db/migration/config/V1__init.sql`
Expected: 只剩 `pipeline_ids VARCHAR(4096) NOT NULL,` 一行（pipeline_id/pipeline_version 在 subscriptions 段已无残留；pipeline_versions 表里的 pipeline_id 是另一张表，不在本段，保留）。

- [ ] **Step 3: Commit**

```bash
git add observe-config/src/main/resources/db/migration/config/V1__init.sql
git commit -m "refactor(config): subscriptions DDL 单列 pipeline_id → JSON pipeline_ids

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: JPA 转换器 `LongListToJsonConverter`

**Files:**
- Create: `observe-config/src/main/java/com/imsw/observe/config/infrastructure/persistence/LongListToJsonConverter.java`

**Interfaces:**
- Produces: `LongListToJsonConverter implements AttributeConverter<List<Long>, String>`，`convertToDatabaseColumn` 把 `List<Long>` 序列化为 JSON 数组字符串（如 `[1001,1002]`），null/empty → null；`convertToEntityAttribute` 反序列化，null/blank → `List.of()`。
- 复用：`com.imsw.observe.kernel.util.JsonUtil`（已有，提供 `toJson` / `fromJson`，参考 `SubscriptionMapper` 的现有用法与 `PipelineRegistryLoader.deserialize` 用 `JsonUtil.fromJson(json, Type)` 的模式）。

- [ ] **Step 1: 确认 JsonUtil 支持 List<Long> 反序列化**

Run: `grep -n "fromJson\|toJson\|TypeReference" observe-kernel/src/main/java/com/imsw/observe/kernel/util/JsonUtil.java`
Expected: 看到 `fromJson(String, Class)` 或 `fromJson(String, Type)` 重载。若只有 `Class` 重载，反序列化用 `Long[]` 数组再转 `List`。

- [ ] **Step 2: 写转换器**

```java
package com.imsw.observe.config.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.imsw.observe.kernel.util.JsonUtil;

/**
 * {@code List<Long>} ↔ JSON 数组字符串（如 {@code [1001,1002]}）。
 *
 * <p>用于 {@code subscriptions.pipeline_ids} 列（B-fanout：一个 subscription 绑多 pipeline）。
 * null/empty 列 → 空 list（实体侧不持有 null）；实体侧空 list → null 列。
 */
@Converter
public class LongListToJsonConverter implements AttributeConverter<List<Long>, String> {

    @Override
    public String convertToDatabaseColumn(final List<Long> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return JsonUtil.toJson(attribute);
    }

    @Override
    public List<Long> convertToEntityAttribute(final String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        Long[] arr = JsonUtil.fromJson(dbData, Long[].class);
        List<Long> result = new ArrayList<>();
        if (arr != null) {
            for (Long v : arr) {
                if (v != null) {
                    result.add(v);
                }
            }
        }
        return result;
    }
}
```

> 若 `JsonUtil.fromJson` 无 `Class<Long[]>` 重载（只收 `Type`），把 `Long[] arr = JsonUtil.fromJson(dbData, Long[].class);` 改为基于 `TypeReference`/`Type` 的等价调用，与 `JsonUtil` 实际签名对齐。以 Step 1 的 grep 结果为准。

- [ ] **Step 3: 写单元测试**

Create `observe-config/src/test/java/com/imsw/observe/config/infrastructure/persistence/LongListToJsonConverterTest.java`:
```java
package com.imsw.observe.config.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LongListToJsonConverterTest {

    private final LongListToJsonConverter converter = new LongListToJsonConverter();

    @Test
    void roundTripsListOfLongs() {
        List<Long> ids = List.of(1001L, 1002L, 1003L);
        String json = converter.convertToDatabaseColumn(ids);
        assertThat(json).isEqualTo("[1001,1002,1003]");
        assertThat(converter.convertToEntityAttribute(json)).containsExactly(1001L, 1002L, 1003L);
    }

    @Test
    void nullAndEmptyMapToNullColumn() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
    }

    @Test
    void nullAndBlankColumnMapToEmptyList() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("  ")).isEmpty();
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `mvn -q -pl observe-config -am test -Dtest=LongListToJsonConverterTest 2>&1 | tail -15`
Expected: `Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 5: Commit**

```bash
git add observe-config/src/main/java/com/imsw/observe/config/infrastructure/persistence/LongListToJsonConverter.java \
        observe-config/src/test/java/com/imsw/observe/config/infrastructure/persistence/LongListToJsonConverterTest.java
git commit -m "feat(config): LongListToJsonConverter for pipeline_ids JSON 列

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 配置态 `SubscriptionDefinition` + `SubscriptionPo` + `SubscriptionMapper`

**Files:**
- Modify: `observe-config/src/main/java/com/imsw/observe/config/domain/SubscriptionDefinition.java`
- Modify: `observe-config/src/main/java/com/imsw/observe/config/infrastructure/persistence/SubscriptionPo.java`
- Modify: `observe-config/src/main/java/com/imsw/observe/config/infrastructure/persistence/SubscriptionMapper.java`

**Interfaces:**
- Consumes: `LongListToJsonConverter`（Task 2）。
- Produces: `SubscriptionDefinition.pipelineIds()` → `List<Long>`；`SubscriptionPo.pipelineIds` 字段（`@Convert(LongListToJsonConverter.class)`）。

- [ ] **Step 1: 改 `SubscriptionDefinition` record**

把字段
```java
        Long pipelineId,
        int pipelineVersion,
```
替换为
```java
        java.util.List<Long> pipelineIds,
```
（位置保持在 `namespace` 之后，与原 `pipelineId` 同位）。在文件顶部 import 区已有 `java.util.Set`，新增 `import java.util.List;`（或用全限定名 `java.util.List`，本 step 用全限定名避免 import 顺序问题——与现有 `java.time.Instant` 全限定名风格一致）。保留 `ActionType`/`Status`/`Concurrent` 三个内嵌枚举不动。

- [ ] **Step 2: 改 `SubscriptionPo`**

把字段
```java
    @Column(name = "pipeline_id", nullable = false)
    public Long pipelineId;

    @Column(name = "pipeline_version", nullable = false)
    public Integer pipelineVersion;
```
替换为
```java
    @Column(name = "pipeline_ids", nullable = false, length = 4096)
    @Convert(converter = LongListToJsonConverter.class)
    public List<Long> pipelineIds = new ArrayList<>();
```
在 import 区加：
```java
import java.util.ArrayList;
import java.util.List;
```
（`ArrayList` 与 `List` 都需要）。保留 `actionType`/`scheduleDelayMs`/`scheduleCorrelationKeyPath` 字段不动。

- [ ] **Step 3: 改 `SubscriptionMapper.toEntity`**

把构造 `SubscriptionDefinition` 的两行
```java
                po.pipelineId,
                nullSafeInt(po.pipelineVersion),
```
替换为
```java
                po.pipelineIds,
```
（位置对应原 `pipelineId`/`pipelineVersion` 两个参数槽，合并为一个 `List<Long>` 参数）。删除 `nullSafeInt` 私有方法（若它只被这两处调用——grep 确认）。

Run: `grep -n "nullSafeInt" observe-config/src/main/java/com/imsw/observe/config/infrastructure/persistence/SubscriptionMapper.java`
若只剩定义行无调用 → 删掉该方法；若还有其它调用 → 保留。

- [ ] **Step 4: 改 `SubscriptionMapper.toPo`**

把
```java
        po.pipelineId = entity.pipelineId();
        po.pipelineVersion = entity.pipelineVersion();
```
替换为
```java
        po.pipelineIds = entity.pipelineIds() == null ? new ArrayList<>() : new ArrayList<>(entity.pipelineIds());
```

- [ ] **Step 5: 编译 config 模块**

Run: `mvn -q -pl observe-config -am compile -DskipTests 2>&1 | tail -20`
Expected: BUILD SUCCESS（控制层 SubscriptionController/SubscriptionDto 还引用旧字段，会编译错——预期，下一步处理；若 error 只来自 controlplane 模块，config 本身应通过。若 config 内部有残留引用，先修 config）。

> 若 controlplane 编译失败属预期，本步只要求 `observe-config` 自身编译通过。下一步 Task 5 修 controlplane。

- [ ] **Step 6: Commit**

```bash
git add observe-config/src/main/java/com/imsw/observe/config/domain/SubscriptionDefinition.java \
        observe-config/src/main/java/com/imsw/observe/config/infrastructure/persistence/SubscriptionPo.java \
        observe-config/src/main/java/com/imsw/observe/config/infrastructure/persistence/SubscriptionMapper.java
git commit -m "refactor(config): SubscriptionDefinition/PO/Mapper 单 pipeline → pipelineIds 列表

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: `SubscriptionCrudService` 校验改为列表

**Files:**
- Modify: `observe-config/src/main/java/com/imsw/observe/config/application/SubscriptionCrudService.java`
- Modify: `observe-config/src/test/java/com/imsw/observe/config/application/SubscriptionCrudServiceTest.java`（已存在，改断言）

**Interfaces:**
- Consumes: `SubscriptionDefinition.pipelineIds()`（Task 3）。
- Produces: `validatePipeline` 校验 `pipelineIds` 非空、每个 id 存在、同 namespace、status=PUBLISHED（不再校验特定版本）。

- [ ] **Step 1: 重写 `validatePipeline`**

把现有 `validatePipeline`（约 121-141 行）整段替换为：
```java
    private void validatePipeline(final SubscriptionDefinition subscription) {
        if (subscription.pipelineIds() == null || subscription.pipelineIds().isEmpty()) {
            throw new IllegalArgumentException("pipelineIds must not be empty");
        }
        for (Long pipelineId : subscription.pipelineIds()) {
            PipelineDefinitionPo def = pipelineDefinitionRepository
                    .findById(pipelineId)
                    .orElseThrow(() -> new IllegalArgumentException("pipeline not found: " + pipelineId));
            // 软隔离铁律（ADR-0002）：subscription 只能引用同 namespace 下的 pipeline。
            if (!def.namespace.equals(subscription.namespace())) {
                throw new IllegalArgumentException("pipeline " + pipelineId
                        + " does not belong to namespace " + subscription.namespace());
            }
            if (!"PUBLISHED".equals(def.status)) {
                throw new IllegalArgumentException("pipeline not published: " + pipelineId);
            }
        }
    }
```
同时删除顶部不再使用的 import：
```java
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionPk;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionRepository;
```
**仅当**这些 import 在本文件其它方法（如无）不再被引用时删除——`pipelineVersionRepository` 字段也一并删（构造器参数同步删）。若删除字段，构造器调用方（Spring 自动注入，无手动 new）不受影响。

> 校验：grep 确认 `pipelineVersionRepository` 在本类其它方法无引用。
> Run: `grep -n "pipelineVersionRepository\|PipelineVersion" observe-config/src/main/java/com/imsw/observe/config/application/SubscriptionCrudService.java`

- [ ] **Step 2: 删除 `pipelineVersionRepository` 字段（若 Step 1 grep 确认可删）**

删除字段声明、构造器参数、构造器赋值三处（约 30、48 行附近）。保留 `pipelineDefinitionRepository`。

- [ ] **Step 3: 更新 `SubscriptionCrudServiceTest`**

先看现有测试用例：
Run: `cat observe-config/src/test/java/com/imsw/observe/config/application/SubscriptionCrudServiceTest.java`

把所有构造 `SubscriptionDefinition` 时传 `pipelineId, pipelineVersion` 两个参数的位置，改成传 `List.of(pipelineId)` 一个参数（与 Task 3 新签名对齐）。例如原本：
```java
new SubscriptionDefinition(null, ns, 1L, 1, mq, topic, ...)
```
改成
```java
new SubscriptionDefinition(null, ns, java.util.List.of(1L), mq, topic, ...)
```

加一个新用例验证多 pipeline + 跨 namespace 拒绝：
```java
    @Test
    void rejectsPipelineFromOtherNamespace() {
        // 复用现有测试的 fixture 风格：建两个 namespace、两个已发布 pipeline（分属不同 ns）。
        // 构造一个 ns-A 的 subscription，pipelineIds 含 ns-B 的 pipeline → 期望抛 IllegalArgumentException。
        // 具体 fixture 调用与现有用例对齐（参考现有 rejectsXxx 用例的 setup）。
    }
```
（若现有测试是 Spring Boot `@DataJpaTest` 风格，沿用其 helper 建 pipeline fixture；若是纯 mock，mock `pipelineDefinitionRepository.findById` 返回不同 namespace 的 PO。）

- [ ] **Step 4: 运行测试**

Run: `mvn -q -pl observe-config -am test -Dtest=SubscriptionCrudServiceTest 2>&1 | tail -20`
Expected: 所有用例 PASS（含新增跨 namespace 拒绝用例）。

- [ ] **Step 5: Commit**

```bash
git add observe-config/src/main/java/com/imsw/observe/config/application/SubscriptionCrudService.java \
        observe-config/src/test/java/com/imsw/observe/config/application/SubscriptionCrudServiceTest.java
git commit -m "refactor(config): SubscriptionCrudService 校验 pipelineIds 列表

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: 控制层 `SubscriptionController` + `SubscriptionDto`

**Files:**
- Modify: `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/SubscriptionController.java`
- Modify: `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/dto/SubscriptionDto.java`

**Interfaces:**
- Consumes: `SubscriptionDefinition.pipelineIds()`（Task 3）。
- Produces: REST API 接受/返回 `pipelineIds: List<Long>`。

- [ ] **Step 1: 改 `SubscriptionController.SubscriptionFields`**

把 record 字段
```java
            @NotNull Long pipelineId,
            int pipelineVersion,
```
替换为
```java
            @NotNull java.util.List<Long> pipelineIds,
```
顶部加 `import java.util.List;`（已存在则跳过）。

`toDomain` 方法里把
```java
                    pipelineId,
                    pipelineVersion,
```
替换为
```java
                    pipelineIds,
```
（与 Task 3 新签名对齐：两参数槽合并为一个 `List<Long>`）。

- [ ] **Step 2: 改 `SubscriptionDto`**

把 record 字段
```java
        Long pipelineId,
        int pipelineVersion,
```
替换为
```java
        java.util.List<Long> pipelineIds,
```
`from` 方法里把
```java
                s.pipelineId(),
                s.pipelineVersion(),
```
替换为
```java
                s.pipelineIds(),
```

- [ ] **Step 3: 编译 controlplane**

Run: `mvn -q -pl observe-controlplane -am compile -DskipTests 2>&1 | tail -20`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/SubscriptionController.java \
        observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/dto/SubscriptionDto.java
git commit -m "refactor(controlplane): subscription API pipelineId → pipelineIds

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 运行态 `Subscription` record

**Files:**
- Modify: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/domain/subscription/Subscription.java`

**Interfaces:**
- Produces: `Subscription.pipelineIds()` → `List<Long>`；保留 `action`/`source`/`fieldFilter`。

- [ ] **Step 1: 改 record 签名**

把
```java
public record Subscription(
        Long id,
        String namespace,
        Long pipelineId,
        int pipelineVersion,
        SourceRef source,
        Condition fieldFilter,
        Action action) {
```
替换为
```java
public record Subscription(
        Long id,
        String namespace,
        java.util.List<Long> pipelineIds,
        SourceRef source,
        Condition fieldFilter,
        Action action) {
```
（保留 `Action action` 不动——延时 spec 处理）。`SourceRef` 内嵌 record 不动。

- [ ] **Step 2: 编译 pipeline 模块（预期 matcher/loader/dispatcher 报错）**

Run: `mvn -q -pl observe-pipeline -am compile -DskipTests 2>&1 | tail -20`
Expected: 编译失败，错误集中在 `DefaultSubscriptionMatcher`（用 `sub.pipelineId()`/`sub.pipelineVersion()`）、`PipelineRegistryLoader`（`new Subscription(... pipelineId, pipelineVersion ...)`）。这些是 Task 7/8 要修的，本步不修。

- [ ] **Step 3: Commit（record 单独提交，编译未全绿但 record 自洽）**

```bash
git add observe-pipeline/src/main/java/com/imsw/observe/pipeline/domain/subscription/Subscription.java
git commit -m "refactor(pipeline): runtime Subscription 单 pipeline → pipelineIds

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: `MatchedSubscription` + `DefaultSubscriptionMatcher`

**Files:**
- Modify: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/SubscriptionMatcher.java`
- Modify: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/subscription/DefaultSubscriptionMatcher.java`
- Modify: `observe-pipeline/src/test/java/com/imsw/observe/pipeline/infrastructure/subscription/DefaultSubscriptionMatcherTest.java`

**Interfaces:**
- Produces: `MatchedSubscription(Subscription sub, List<Pipeline> pipelines)`；matcher 返回的每个 matched 携带解析后的可执行 pipeline 列表（已过滤 null/版本不符——版本不再校验，跟 currentVersion）。

- [ ] **Step 1: 改 `MatchedSubscription` record**

把 `SubscriptionMatcher.java` 的 record：
```java
    record MatchedSubscription(
            com.imsw.observe.pipeline.domain.subscription.Subscription subscription,
            com.imsw.observe.pipeline.domain.Pipeline pipeline) {}
```
替换为
```java
    record MatchedSubscription(
            com.imsw.observe.pipeline.domain.subscription.Subscription subscription,
            java.util.List<com.imsw.observe.pipeline.domain.Pipeline> pipelines) {}
```

- [ ] **Step 2: 改 `DefaultSubscriptionMatcher.match` + `tryMatch`**

把 `match` 方法里
```java
        for (Subscription sub : snapshot.subscriptionsFor(event)) {
            Pipeline pipeline = tryMatch(sub, event, snapshot);
            if (pipeline != null) {
                matched.add(new MatchedSubscription(sub, pipeline));
            }
        }
```
替换为
```java
        for (Subscription sub : snapshot.subscriptionsFor(event)) {
            List<Pipeline> pipelines = tryMatch(sub, event, snapshot);
            if (!pipelines.isEmpty()) {
                matched.add(new MatchedSubscription(sub, pipelines));
            }
        }
```
把 `tryMatch` 返回类型 `Pipeline` → `List<Pipeline>`，末尾逻辑改为：
```java
    private static List<Pipeline> tryMatch(
            final Subscription sub, final Event event, final PipelineRegistry.Snapshot snapshot) {
        if (!matchesSource(sub, event)) {
            return List.of();
        }
        if (!passesFieldFilter(sub, event)) {
            return List.of();
        }
        // 扇出：遍历 pipelineIds，过滤掉 null（pipeline 不存在/未发布）。版本不再校验——跟 currentVersion。
        List<Pipeline> result = new ArrayList<>();
        for (Long id : sub.pipelineIds()) {
            Pipeline pipeline = snapshot.pipelineById(id);
            if (pipeline != null) {
                result.add(pipeline);
            }
        }
        return result;
    }
```

- [ ] **Step 3: 改 `DefaultSubscriptionMatcherTest`**

所有 `new Subscription(10L, "smoke", 1L, 1, new SourceRef(...), ..., new Action.Run())` 构造，把 `1L, 1`（pipelineId, version）替换为 `java.util.List.of(1L)`（pipelineIds）。例如：
```java
        Subscription sub = new Subscription(
                10L,
                "smoke",
                java.util.List.of(1L),
                new Subscription.SourceRef(...),
                new Condition.Compare(...),
                new Action.Run());
```

删除 `skipsWhenPipelineVersionMismatch` 测试（版本不再校验，该语义消失）——替换为一个验证"部分 pipeline 缺失只返回存在的"用例：
```java
    @Test
    void returnsOnlyExistingPipelinesWhenSomeMissing() {
        Pipeline p1 = pipeline(1L, 1);
        // p2 (id=2) 不在 registry → 应被过滤
        Subscription sub = new Subscription(
                10L,
                "smoke",
                java.util.List.of(1L, 2L),
                new Subscription.SourceRef(
                        null, null, "trade_db", "orders", Set.of(), SourceType.CDC, null, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(1L, p1), List.of(sub)));

        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);
        List<SubscriptionMatcher.MatchedSubscription> matched =
                matcher.match(cdcEvent("trade_db", "orders", CdcOp.INSERT, Map.of()));
        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).pipelines()).containsExactly(p1);
    }

    @Test
    void skipsWhenAllPipelinesMissing() {
        // pipelineIds 全部不在 registry → matched 为空
        Subscription sub = new Subscription(
                10L,
                "smoke",
                java.util.List.of(99L),
                new Subscription.SourceRef(
                        null, null, "trade_db", "orders", Set.of(), SourceType.CDC, null, null, null),
                null,
                new Action.Run());
        PipelineRegistry registry = new PipelineRegistry();
        registry.replace(PipelineRegistry.Snapshot.loaded(Map.of(), List.of(sub)));
        SubscriptionMatcher matcher = new DefaultSubscriptionMatcher(registry);
        assertThat(matcher.match(cdcEvent("trade_db", "orders", CdcOp.INSERT, Map.of()))).isEmpty();
    }
```
现有 `matchesCdcByDbTableOpAndFieldFilter` / `matchesTickBySourceName` / `matchesApiBySourceName` 用例：把 `match(hit)` 后若有断言 `hasSize(1)` 检查的是 matched 数（仍为 1，因为只有 1 个 sub），保持；但需确认没有断言 `.pipeline()`（旧字段）——若有，改为 `.pipelines()`。

- [ ] **Step 4: 运行 matcher 测试**

Run: `mvn -q -pl observe-pipeline -am test -Dtest=DefaultSubscriptionMatcherTest 2>&1 | tail -20`
Expected: 所有用例 PASS（含两个新增用例）。

- [ ] **Step 5: Commit**

```bash
git add observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/SubscriptionMatcher.java \
        observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/subscription/DefaultSubscriptionMatcher.java \
        observe-pipeline/src/test/java/com/imsw/observe/pipeline/infrastructure/subscription/DefaultSubscriptionMatcherTest.java
git commit -m "feat(pipeline): MatchedSubscription 携带 List<Pipeline> 支持扇出

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: `SourceDispatcher` 扇出

**Files:**
- Modify: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/SourceDispatcher.java`
- Modify: `observe-pipeline/src/test/java/com/imsw/observe/pipeline/application/SourceDispatcherTest.java`

**Interfaces:**
- Consumes: `MatchedSubscription.pipelines()`（Task 7）。
- Produces: `dispatch` 对每个 matched 内层 for 每个 pipeline 调 `submitMatched(sub, pipeline, event)`；每个 (sub,pipeline) 独立 inFlight permit / runnerPool 提交 / 失败隔离。

- [ ] **Step 1: 改 `dispatch` + `submitMatched`**

把 `dispatch`：
```java
    private void dispatch(final Event event) throws InterruptedException {
        List<SubscriptionMatcher.MatchedSubscription> matched = matcher.match(event);
        if (matched.isEmpty()) {
            return;
        }
        for (SubscriptionMatcher.MatchedSubscription m : matched) {
            submitMatched(m, event);
        }
    }

    private void submitMatched(final SubscriptionMatcher.MatchedSubscription m, final Event event)
            throws InterruptedException {
        Pipeline pipeline = m.pipeline();
        Long subscriptionId = m.subscription().id();
        if (delayedActionHandler.handle(m.subscription(), event, pipeline)) {
            return;
        }
        inFlight.acquire();
        try {
            runnerPool.execute(() -> runAndRelease(pipeline, event, subscriptionId));
        } catch (RuntimeException e) {
            inFlight.release();
            LOG.warn("runnerPool.execute rejected event for subscription {}", subscriptionId, e);
        }
    }
```
替换为
```java
    private void dispatch(final Event event) throws InterruptedException {
        List<SubscriptionMatcher.MatchedSubscription> matched = matcher.match(event);
        if (matched.isEmpty()) {
            return;
        }
        for (SubscriptionMatcher.MatchedSubscription m : matched) {
            // 扇出：subscription 维度外层 for，pipeline 维度内层 for。
            // 顺序提交、并发执行（runnerPool 多线程）；每个 (sub,pipeline) 独立 inFlight permit + 失败隔离。
            for (Pipeline pipeline : m.pipelines()) {
                submitMatched(m.subscription(), pipeline, event);
            }
        }
    }

    private void submitMatched(
            final com.imsw.observe.pipeline.domain.subscription.Subscription subscription,
            final Pipeline pipeline,
            final Event event) throws InterruptedException {
        Long subscriptionId = subscription.id();
        // 延时 handler 保留现状（延时 spec 处理）；扇出后每个 (sub,pipeline) 各调一次。
        if (delayedActionHandler.handle(subscription, event, pipeline)) {
            return;
        }
        inFlight.acquire();
        try {
            runnerPool.execute(() -> runAndRelease(pipeline, event, subscriptionId));
        } catch (RuntimeException e) {
            inFlight.release();
            LOG.warn("runnerPool.execute rejected event for subscription {}", subscriptionId, e);
        }
    }
```
`runAndRelease` 不变。

- [ ] **Step 2: 编译 pipeline 模块**

Run: `mvn -q -pl observe-pipeline -am compile -DskipTests 2>&1 | tail -20`
Expected: BUILD SUCCESS（pipeline 模块 main 全绿）。

- [ ] **Step 3: 改 `SourceDispatcherTest`**

先看现有测试如何断言：
Run: `cat observe-pipeline/src/test/java/com/imsw/observe/pipeline/application/SourceDispatcherTest.java`

现有用例若构造 `MatchedSubscription(sub, singlePipeline)` 并喂给 dispatcher，改为 `MatchedSubscription(sub, List.of(singlePipeline))`。若用例 mock `matcher.match` 返回 `List<MatchedSubscription>`，确保每个 matched 的 `.pipelines()` 是 list。

加一个验证扇出失败隔离的用例（若现有测试有 mock runner 的基础设施就复用，否则参考 `SourceDispatcherBackpressureTest` 的 mock 风格）：
```java
    @Test
    void fansOutToMultiplePipelinesWithFailureIsolation() {
        // 构造一个 matched(sub, [p1, p2])。
        // mock runner：p1 抛 RuntimeException，p2 正常。
        // 断言：runner.run 对 p1 和 p2 各被调用一次；p1 的异常不阻止 p2 执行。
        // （具体 mock 写法与现有 SourceDispatcherTest 的 runner mock 对齐。）
    }
```

- [ ] **Step 4: 运行 dispatcher 测试**

Run: `mvn -q -pl observe-pipeline -am test -Dtest=SourceDispatcherTest,SourceDispatcherBackpressureTest 2>&1 | tail -25`
Expected: 所有用例 PASS（含新增扇出失败隔离用例）。

- [ ] **Step 5: Commit**

```bash
git add observe-pipeline/src/main/java/com/imsw/observe/pipeline/application/SourceDispatcher.java \
        observe-pipeline/src/test/java/com/imsw/observe/pipeline/application/SourceDispatcherTest.java
git commit -m "feat(pipeline): SourceDispatcher 内层 for 扇出多 pipeline, 失败隔离

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: `PipelineRegistryLoader` 加载 pipelineIds

**Files:**
- Modify: `observe-config/src/main/java/com/imsw/observe/config/application/PipelineRegistryLoader.java`

**Interfaces:**
- Consumes: `SubscriptionPo.pipelineIds`（Task 3）、运行态 `Subscription` 新签名（Task 6）。
- Produces: 加载时 `pipelineIds` 列表里至少一个 id 在 `pipelines` map 才纳入订阅。

- [ ] **Step 1: 改 loader 的纳入判断**

把
```java
        for (SubscriptionPo subPo : subscriptionRepository.findAll()) {
            if (!"ACTIVE".equals(subPo.status)) {
                continue;
            }
            if (!pipelines.containsKey(subPo.pipelineId)) {
                continue;
            }
            subscriptions.add(toPipelineSubscription(subPo));
        }
```
替换为
```java
        for (SubscriptionPo subPo : subscriptionRepository.findAll()) {
            if (!"ACTIVE".equals(subPo.status)) {
                continue;
            }
            // 扇出：pipelineIds 至少一个 id 在已加载 pipelines 里才纳入订阅；全部缺失则跳过。
            if (subPo.pipelineIds == null || subPo.pipelineIds.isEmpty()) {
                continue;
            }
            boolean anyPresent = false;
            for (Long pid : subPo.pipelineIds) {
                if (pipelines.containsKey(pid)) {
                    anyPresent = true;
                    break;
                }
            }
            if (!anyPresent) {
                continue;
            }
            subscriptions.add(toPipelineSubscription(subPo));
        }
```

- [ ] **Step 2: 改 `toPipelineSubscription`**

把
```java
        return new Subscription(
                entity.id(),
                entity.namespace(),
                entity.pipelineId(),
                entity.pipelineVersion(),
                source,
                entity.fieldFilter(),
                toAction(entity));
```
替换为
```java
        return new Subscription(
                entity.id(),
                entity.namespace(),
                entity.pipelineIds(),
                source,
                entity.fieldFilter(),
                toAction(entity));
```
（`toAction` 不动——延时 spec 处理；`source` 构造不动）。

- [ ] **Step 3: 编译全量**

Run: `mvn -q compile -DskipTests 2>&1 | tail -20`
Expected: BUILD SUCCESS（所有 main 模块编译通过）。

- [ ] **Step 4: Commit**

```bash
git add observe-config/src/main/java/com/imsw/observe/config/application/PipelineRegistryLoader.java
git commit -m "refactor(config): PipelineRegistryLoader 加载 pipelineIds, 至少一个存在才纳入

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: 全量测试 + 回归

**Files:**
- 无新增；运行全量验证。

- [ ] **Step 1: 全量编译 + 单元/集成测试**

Run: `mvn -q install -DskipITs 2>&1 | grep -E "Tests run:|BUILD (SUCCESS|FAILURE)|ERROR" | tail -30`
Expected: BUILD SUCCESS，所有模块测试通过。

- [ ] **Step 2: 修复回归（若有）**

若有测试失败，逐一修复（常见：测试里 `new SubscriptionDefinition(...)` 或 `new Subscription(...)` 仍用旧签名；DTO 测试断言旧字段）。修复后重跑该模块测试。

- [ ] **Step 3: 端到端回归**

Run: `mvn -q -pl observe-bootstrap test -Dtest=EndToEndFlowTest 2>&1 | tail -15`
Expected: PASS（端到端流程：CDC 事件 → 匹配 → 执行 → 告警/execution 落库，不受扇出改造影响）。

- [ ] **Step 4: 最终提交（若有回归修复）**

```bash
git add -A
git commit -m "test(*): 扇出改造回归修复

Co-Authored-By: Claude <noreply@anthropic.com>"
```
若无回归修复，跳过本步。

---

## Self-Review 记录

**Spec 覆盖**：D1（pipelineIds，Task 1/3/5/6）、D2（删 pipeline_version，Task 1/3/6）、D3（JSON 列，Task 1/2）、D4（内层 for 顺序提交并发执行，Task 8）、D5（每 sub,pipeline 独立隔离，Task 8 测试）—— 全覆盖。§5 失败隔离（Task 8）、§6 loader（Task 9）、§7 测试（Task 2/4/7/8/10）—— 全覆盖。

**边界**：明确不动 actionType/Action/DelayedActionHandler（Global Constraints + Task 8 注释保留 handler 调用）。

**类型一致性**：`pipelineIds` 类型全链路 `List<Long>`（Definition/PO/Mapper/Controller/Dto/运行态 Subscription/matcher tryMatch）—— 一致。`MatchedSubscription.pipelines()`（Task 7）↔ `m.pipelines()`（Task 8）—— 一致。`submitMatched(subscription, pipeline, event)` 签名（Task 8 定义）—— 调用方一致。

**待执行时确认项**（非占位符，是依赖外部文件形态的动态确认）：
- Task 2 Step 1：`JsonUtil.fromJson` 的 `Class` vs `Type` 重载——以 grep 结果对齐。
- Task 4 Step 1/2：`pipelineVersionRepository` 是否在 `SubscriptionCrudService` 其它方法被引用——以 grep 决定是否删字段。
- Task 3 Step 3：`nullSafeInt` 是否还有其它调用——以 grep 决定是否删方法。
- Task 4/7/8 Step 3：现有测试文件的具体 fixture 风格——以 `cat` 结果对齐（这些是测试代码细节，不能凭空写，需读现有测试）。
