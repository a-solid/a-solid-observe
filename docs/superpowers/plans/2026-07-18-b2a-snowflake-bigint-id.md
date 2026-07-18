# B2a 标识层地基（snowflake BIGINT 主键）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把所有资源表的主键从 `VARCHAR` 改为应用层 snowflake 生成的 `BIGINT`（非 DB 自增），跨表引用列同步改 BIGINT；引入 `SnowflakeIdGenerator`（workerId 一期硬编码 = 1）。

**Architecture:** 在 kernel 新增 `SnowflakeIdGenerator`（Twitter snowflake 变体：时间戳 + workerId + 序列号，趋势递增、跨实例唯一）。所有 PO 的 `@Id` 从 `String` 改 `Long`，各 Repository 的 `JpaRepository<T, String>` 改 `JpaRepository<T, Long>`，跨表引用列（`alerts.execution_id`、`alerts_evidence.alert_id`、`failed_executions.execution_id` 等）从 String 改 Long。ID 由应用层在 INSERT 前调 `SnowflakeIdGenerator.next()` 分配，不用 DB 自增。开发期 H2 in-memory + `ddl-auto: update`：表每次启动重建，无数据迁移问题；SQL 参考脚本同步更新以保持真实。

**Tech Stack:** Java 17、Spring Boot、JPA/Hibernate、H2（开发）、JUnit 5、AssertJ。

> **本子计划是 B2 的第 1 份（共 4 份）**。B2 顺序：**B2a（本份，id/snowflake）→ B2b（namespace + CRUD）→ B2c（命名重构 SubscriptionDefinition）→ B2d（延时端口）**。本份只动 id 类型与 snowflake 生成，**不**引入 namespace（那是 B2b）、**不**重命名 Subscription（那是 B2c）、**不**改 Event 模型（那是 B3）。

## Global Constraints

- 设计权威：`CONTEXT.md` Namespacing & Identity 节 + `docs/adr/0003-snowflake-bigint-id-and-business-key.md`。本份对应 ADR-0003 的「snowflake BIGINT 主键」部分（业务键 `(namespace, name)` 留给 B2b）。
- snowflake `workerId` 一期硬编码 = 1、`datacenterId` = 0（单 worker；多 worker 协调为二期）。
- **非 DB 自增**：id 由 `SnowflakeIdGenerator` 在应用层分配，PO 不用 `@GeneratedValue`。
- 开发库 H2 `jdbc:h2:mem:observe` + `ddl-auto: update`：表每次启动重建，**无持久化数据**，改 id 类型无迁移问题。
- Flyway 未接入 pom（SQL 脚本是参考/文档，不被执行）；本份**同步更新 SQL 脚本**保持真实，但它们不驱动开发库。
- 每批结束（B2a 全部任务完成）必须 `mvn compile` + `mvn test` 全绿。
- 不使用 FK（项目铁律）；跨表引用靠应用层。
- 本份**不**改对外 API 路径（controlplane 仍用 `/{id}` 寻址，id 从 String 变 Long 但路径形态不变；namespace+name 业务键寻址留给 B2b）。

## File Structure

**Create:**
- `observe-kernel/src/main/java/com/imsw/observe/kernel/util/SnowflakeIdGenerator.java` —— snowflake id 生成器。
- `observe-kernel/src/test/java/com/imsw/observe/kernel/util/SnowflakeIdGeneratorTest.java` —— 生成器单测（唯一性、趋势递增、跨实例不冲突假设）。

**Modify（PO @Id + 跨表引用列 String→Long）:**
- `observe-config/.../PipelineDefinitionPo.java` — `id` String→Long。
- `observe-config/.../PipelineVersionPo.java` + `PipelineVersionPk.java` — 复合键 `pipelineId` String→Long。
- `observe-config/.../SubscriptionPo.java` — `id` String→Long；`pipelineId` String→Long。
- `observe-alerting/.../alert/AlertPo.java` — `id` String→Long；`executionId` String→Long。
- `observe-alerting/.../evidence/EvidencePo.java` — `alertId` String→Long（PK；B5 改 1:N 时再调结构）。
- `observe-pipeline/.../ExecutionPo.java` — `id` String→Long；`subscriptionId` String→Long；`pipelineId` String→Long。
- `observe-pipeline/.../FailedExecutionPo.java` — `id` String→Long；`executionId` String→Long；`subscriptionId` String→Long；`pipelineId` String→Long。

**Modify（Repository 泛型 String→Long）:**
- 所有 `JpaRepository<XxxPo, String>` → `JpaRepository<XxxPo, Long>`（config/alerting/pipeline 各 Repository）。

**Modify（Mapper / Service / Recorder：id 从 UUID 字符串改 snowflake long）:**
- `observe-config/.../PipelineDefinitionMapper`、`SubscriptionMapper`（如有 id 转换）。
- `observe-alerting/.../AlertMapper`、`EvidenceMapper`、`DefaultAlertSink`（id 生成从 `UUID.randomUUID().toString()` 改 `snowflake.next()`）。
- `observe-pipeline/.../JpaExecutionRecorder`（execution/failedExecution id 生成从 UUID 改 snowflake）。
- config 领域 record（`PipelineDefinition`、`Subscription`、`PipelineVersion`）的 id 字段 String→Long。
- alerting 领域 record（`AlertEntity`、`EvidenceEntity`）id 字段 String→Long。
- pipeline 领域（`Execution`、`FailedExecution` record，若有）id String→Long。

**Modify（DTO / Controller：id String→Long）:**
- `observe-controlplane/.../dto/*Dto.java`、`*Controller.java` 的 id 字段/`@PathVariable` String→Long。

**Modify（装配 + SQL 参考）:**
- `observe-bootstrap/.../config/*.java` — 装配 `SnowflakeIdGenerator` bean，注入需要生成 id 的 service/recorder。
- `observe-*/src/main/resources/db/migration/<module>/V1__init.sql` — `id VARCHAR(...)` → `id BIGINT`，跨表引用列同步；保持 PRIMARY KEY 语义。

**接口边界**（本份定义、后续份依赖）：
- `SnowflakeIdGenerator`（kernel.util）：
  - `public SnowflakeIdGenerator(long workerId, long datacenterId)` —— 一期 new `SnowflakeIdGenerator(1L, 0L)`。
  - `public synchronized long next()` —— 返回趋势递增、唯一的 long id。

---

## Task 1: SnowflakeIdGenerator（kernel，含单测）

**Files:**
- Create: `observe-kernel/src/main/java/com/imsw/observe/kernel/util/SnowflakeIdGenerator.java`
- Test: `observe-kernel/src/test/java/com/imsw/observe/kernel/util/SnowflakeIdGeneratorTest.java`

**Interfaces:**
- Consumes: 无（kernel 零依赖）。
- Produces: `SnowflakeIdGenerator` 类，签名见上「接口边界」。后续所有任务通过注入或 new 使用 `next()`。

- [ ] **Step 1: 写失败测试 `SnowflakeIdGeneratorTest`**

```java
package com.imsw.observe.kernel.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    void nextReturnsPositiveTrendAscending() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1L, 0L);
        long a = gen.next();
        long b = gen.next();
        long c = gen.next();
        assertThat(a).isPositive();
        assertThat(b).isGreaterThan(a);
        assertThat(c).isGreaterThan(b);
    }

    @Test
    void nextIsUniqueAcrossManyCallsOneThread() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1L, 0L);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(gen.next())).isTrue();
        }
    }

    @Test
    void nextIsUniqueAcrossThreadsSharingOneGenerator() throws InterruptedException {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(1L, 0L);
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        int threads = 8;
        int perThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        long id = gen.next();
                        assertThat(seen.add(id)).as("duplicate id %d", id).isTrue();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(seen).hasSize((long) threads * perThread);
    }

    @Test
    void differentWorkerIdsProduceDisjointRanges() {
        SnowflakeIdGenerator gen1 = new SnowflakeIdGenerator(1L, 0L);
        SnowflakeIdGenerator gen2 = new SnowflakeIdGenerator(2L, 0L);
        Set<Long> from1 = new HashSet<>();
        Set<Long> from2 = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            from1.add(gen1.next());
            from2.add(gen2.next());
        }
        for (Long id : from2) {
            assertThat(from1).doesNotContain(id);
        }
    }
}
```

- [ ] **Step 2: 运行测试，验证失败（类不存在）**

Run: `mvn -q -pl observe-kernel test -Dtest=SnowflakeIdGeneratorTest`
Expected: 编译失败 / 类找不到。

- [ ] **Step 3: 实现 `SnowflakeIdGenerator`**

```java
package com.imsw.observe.kernel.util;

/**
 * Snowflake id 生成器：64-bit，趋势递增，跨实例唯一（不同 workerId/datacenterId 不冲突）。
 *
 * <p>布局（与 Twitter snowflake 一致）：
 * <ul>
 *   <li>1 bit 符号位（恒 0，正数）
 *   <li>41 bit 毫秒时间戳（相对自定义纪元，约 69 年）
 *   <li>5 bit datacenterId（0-31）
 *   <li>5 bit workerId（0-31）
 *   <li>12 bit 同毫秒序列号（0-4095）
 * </ul>
 *
 * <p>线程安全：{@link #next()} synchronized，保证同毫秒序列号不重。
 * 一期单 worker，workerId 硬编码 1；多 worker 时由协调分配 workerId（二期）。
 */
public final class SnowflakeIdGenerator {

    private static final long EPOCH = 1_704_067_200_000L; // 2024-01-01T00:00:00Z 毫秒

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS); // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS); // 31

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS); // 4095

    private final long workerId;
    private final long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(final long workerId, final long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId must be between 0 and " + MAX_WORKER_ID + " but was " + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                    "datacenterId must be between 0 and " + MAX_DATACENTER_ID + " but was " + datacenterId);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long next() {
        long now = timeMillis();
        if (now < lastTimestamp) {
            // 时钟回拨：等待到上次时间。一期容忍小幅回拨。
            now = lastTimestamp;
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                // 同毫秒序列耗尽，等下一毫秒
                now = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(final long lastTimestamp) {
        long now = timeMillis();
        while (now <= lastTimestamp) {
            now = timeMillis();
        }
        return now;
    }

    private static long timeMillis() {
        return System.currentTimeMillis();
    }
}
```

- [ ] **Step 4: 运行测试，验证通过**

Run: `mvn -q -pl observe-kernel test -Dtest=SnowflakeIdGeneratorTest`
Expected: 4 个测试全绿（含 8 线程 × 20000 唯一性）。

- [ ] **Step 5: 提交**

```bash
git add observe-kernel/src/main/java/com/imsw/observe/kernel/util/SnowflakeIdGenerator.java \
        observe-kernel/src/test/java/com/imsw/observe/kernel/util/SnowflakeIdGeneratorTest.java
git commit -m "$(cat <<'EOF'
feat(kernel): add SnowflakeIdGenerator (BIGINT id, workerId=1 for v1)

64-bit 趋势递增、跨实例唯一的 id 生成器（Twitter snowflake 布局）。一期单 worker workerId 硬编码 1。后续 B2 任务用它替代各表的 UUID 字符串主键（ADR-0003）。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 装配 SnowflakeIdGenerator bean（bootstrap）

**Files:**
- Create/Modify: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/config/CoreConfig.java`（若不存在则新建；若已有则加 bean）。
- Verify first: 检查 bootstrap 是否已有 `CoreConfig` 或类似通用 config；若 `WorkerConfig` 更合适则加在那里。

**Interfaces:**
- Consumes: Task 1 的 `SnowflakeIdGenerator`。
- Produces: Spring bean `SnowflakeIdGenerator`（workerId=1, datacenterId=0），可供 service/recorder 注入。

- [ ] **Step 1: 确认 bootstrap config 现状**

Run: `find observe-bootstrap/src/main/java -name "*Config.java"` 并查看哪个 config 装配通用 bean。
Expected: 看到 `WorkerConfig` / `CoreConfig` / `AlertingPipelineConfig` 等。选一个放 `SnowflakeIdGenerator` bean（推荐 `CoreConfig` 或 `WorkerConfig` 顶层）。

- [ ] **Step 2: 加 bean**

在选定的 config 类中加（以 `CoreConfig` 为例，若用 `WorkerConfig` 则同理）：

```java
@Bean
public com.imsw.observe.kernel.util.SnowflakeIdGenerator snowflakeIdGenerator() {
    // 一期单 worker，workerId 硬编码 1；多 worker 时由协调分配（二期）
    return new com.imsw.observe.kernel.util.SnowflakeIdGenerator(1L, 0L);
}
```

（若新建 `CoreConfig`，加 `@Configuration` 注解 + 必要 import。）

- [ ] **Step 3: 编译验证**

Run: `mvn -q -pl observe-bootstrap -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 提交**

```bash
git add observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/config/
git commit -m "$(cat <<'EOF'
feat(bootstrap): wire SnowflakeIdGenerator bean (workerId=1)

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 改 config 模块 PO/Repository/Mapper/领域 record 的 id（pipelines + pipeline_versions + subscriptions）

**Files:**
- Modify: `observe-config/.../PipelineDefinitionPo.java`（`id` String→Long）
- Modify: `observe-config/.../PipelineVersionPo.java`（`pipelineId` String→Long）+ `PipelineVersionPk.java`（`pipelineId` String→Long）
- Modify: `observe-config/.../SubscriptionPo.java`（`id` String→Long；`pipelineId` String→Long）
- Modify: `observe-config/.../PipelineDefinitionRepository.java`、`PipelineVersionRepository.java`、`SubscriptionRepository.java`（泛型 String→Long）
- Modify: `observe-config/.../PipelineDefinitionMapper.java`、`SubscriptionMapper.java`（id 转换 String→Long）
- Modify: `observe-config/domain/PipelineDefinition.java`、`Subscription.java`、`PipelineVersion.java`（id 字段 String→Long）
- Modify: `observe-config/application/PipelineCrudService.java`、`VersionPublishService.java`、`SubscriptionCrudService.java`、`PipelineRegistryLoader.java`（id 类型 String→Long；id 生成从入参 String 改 snowflake 注入）

**Interfaces:**
- Consumes: Task 1/2 的 `SnowflakeIdGenerator`（注入 service 用于新建时分配 id）。
- Produces: config 模块所有 id 改 Long。下游 pipeline 模块 `Subscription` 运行态、controlplane DTO 需在本份后续任务同步。

> **重要约束（避免编译断裂）**：config 模块 id 改 Long 后，controlplane（DTO/Controller）和 pipeline（运行态 Subscription）会立刻编译失败（它们引用 config 的 String id）。**本任务范围限于 config 模块内部 + 紧耦合的 mapper**；controlplane 和 pipeline 的连带修改放 Task 6（DTO/Controller）和 Task 4（pipeline PO/领域）。本份批末（Task 7 全量测试）才整体绿——符合用户约束「B2a 内部不强求每提交编译通过，批末正确即可」。

- [ ] **Step 1: 改 PipelineDefinitionPo + PipelineVersionPo + PipelineVersionPk + SubscriptionPo 的 id 列类型**

`PipelineDefinitionPo.java`：
```java
// 原：@Column(name = "id", length = 64, nullable = false) public String id;
@Id
@Column(name = "id", nullable = false)
public Long id;
```

`PipelineVersionPo.java`：
```java
// 原：@Column(name = "pipeline_id", length = 64, nullable = false) public String pipelineId;
@Id
@Column(name = "pipeline_id", nullable = false)
public Long pipelineId;
```

`PipelineVersionPk.java`：
```java
public Long pipelineId;  // 原 String
public Integer version;
// 构造器、equals、hashCode 同步改 String→Long
```

`SubscriptionPo.java`：
```java
// id：String→Long
// pipelineId：String→Long
```

- [ ] **Step 2: 改三个 Repository 泛型**

`PipelineDefinitionRepository extends JpaRepository<PipelineDefinitionPo, Long>`
`PipelineVersionRepository extends JpaRepository<PipelineVersionPo, PipelineVersionPk>`（Pk 内部已改 Long，无需变泛型）
`SubscriptionRepository extends JpaRepository<SubscriptionPo, Long>`

- [ ] **Step 3: 改 config 领域 record 的 id 字段**

`PipelineDefinition` record：`String id` → `Long id`。
`PipelineVersion` record：`String pipelineId` → `Long pipelineId`。
`Subscription` record：`String id` → `Long id`；`String pipelineId` → `Long pipelineId`。

- [ ] **Step 4: 改 Mapper**

`PipelineDefinitionMapper.toPo/toEntity`：id String→Long 转换。
`SubscriptionMapper.toPo/toEntity`：id/pipelineId String→Long。
`PipelineVersionMapper`：pipelineId String→Long。

- [ ] **Step 5: 改 service / loader（id 生成改 snowflake，类型 String→Long）**

- `PipelineCrudService.create(...)`：原入参 `String id`（调用方传）→ 改为内部用 `SnowflakeIdGenerator.next()` 分配。构造器注入 `SnowflakeIdGenerator`。`find/update/archive` 的 id 入参 String→Long。
- `VersionPublishService`：`saveDraft/publish/archive` 的 pipelineId 入参 String→Long。
- `SubscriptionCrudService`：`create/find/update/delete` 的 id/pipelineId String→Long；create 时 id 用 snowflake 分配。
- `PipelineRegistryLoader.toPipelineSubscription`：subPo.id/pipelineId 已是 Long，运行态 Subscription（pipeline 模块）的 id 字段要在 Task 4 同步改 Long。

> 注意：运行态 `Subscription`（observe-pipeline.domain.subscription.Subscription）的 `id`/`pipelineId` 当前是 String。Loader 把 config 的 Long 传给它——**本步会让 pipeline 模块编译失败**。这是预期的（批末修），Task 4 修 pipeline 模块。

- [ ] **Step 6: 编译 config 模块自身（预期可能因 pipeline 运行态未改而失败，记录但继续）**

Run: `mvn -q -pl observe-config compile`
Expected: config 自身编译可能因 import pipeline 的运行态 Subscription 而连带失败——这是已知的，Task 4 修。本步仅确认 config 内部 PO/Repository/Mapper/领域 record 改完。

- [ ] **Step 7: 提交（config 模块 id 改 Long）**

```bash
git add observe-config/
git commit -m "$(cat <<'EOF'
refactor(config): switch pipeline/subscription ids to snowflake BIGINT

pipelines/pipeline_versions/subscriptions 的 id 与 pipeline_id 列由 VARCHAR 改 BIGINT；Repository 泛型 String→Long；service 用 SnowflakeIdGenerator 分配 id（ADR-0003）。pipeline 运行态/controlplane 的连带修改在后续任务。

NOTE: 本提交跨越模块边界，pipeline/controlplane 暂未同步，批末统一绿。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: 改 pipeline 模块 PO/Repository/领域 record 的 id（executions + failed_executions + 运行态 Subscription/Pipeline）

**Files:**
- Modify: `observe-pipeline/.../ExecutionPo.java`（`id`/`subscriptionId`/`pipelineId` String→Long）
- Modify: `observe-pipeline/.../FailedExecutionPo.java`（`id`/`executionId`/`subscriptionId`/`pipelineId` String→Long）
- Modify: `observe-pipeline/.../ExecutionRepository.java`、`FailedExecutionRepository.java`（泛型 String→Long）
- Modify: `observe-pipeline/.../JpaExecutionRecorder.java`（id 生成从 `UUID.randomUUID().toString()` 改 `snowflake.next()`；executionId 引用 Long）
- Modify: `observe-pipeline/domain/Pipeline.java`（`id` String→Long？**先确认**：Pipeline 运行态 id 来自 PipelineVersion.definitionJson 反序列化，与 config 的 pipelineId 一致——改 Long）
- Modify: `observe-pipeline/domain/subscription/Subscription.java`（运行态 `id`/`pipelineId` String→Long）
- Modify: `observe-pipeline/application/PipelineRegistry.java` + `Snapshot`（`pipelinesById` Map<String,Pipeline>→Map<Long,Pipeline>；`pipelineById(Long)`）
- Modify: `observe-pipeline/application/SubscriptionMatcher.java` + `DefaultSubscriptionMatcher`（MatchedSubscription 引用 id Long）
- Modify: `observe-pipeline/infrastructure/delayed/InMemoryDelayedEventStore.java` + `application/DelayedActionHandler.java`（subscriptionId 引用 Long）
- Modify: `observe-bootstrap/.../config/AlertingPipelineConfig.java`（JpaExecutionRecorder bean 注入 SnowflakeIdGenerator）
- Modify: `observe-bootstrap/.../EndToEndFlowTest.java`、`EngineSmokeTest.java`（测试 fixture id String→Long）

**Interfaces:**
- Consumes: Task 1/2 `SnowflakeIdGenerator`；Task 3 config 模块已改 Long（运行态 Subscription/Pipeline 字段类型对齐 config）。
- Produces: pipeline 模块所有 id 改 Long；运行态 Subscription/Pipeline 与 config 对齐。

- [ ] **Step 1: 改 ExecutionPo + FailedExecutionPo**

`ExecutionPo.java`：`id`/`subscriptionId`/`pipelineId` String→Long（去 `length` 属性，Long 无需 length）。
`FailedExecutionPo.java`：`id`/`executionId`/`subscriptionId`/`pipelineId` String→Long。

- [ ] **Step 2: 改 Repository 泛型 + ExecutionQueryService**

`ExecutionRepository extends JpaRepository<ExecutionPo, Long>`。
`FailedExecutionRepository extends JpaRepository<FailedExecutionPo, Long>`。
`ExecutionQueryService.findExecutions/findExecution` 等 id 入参 String→Long。

- [ ] **Step 3: 改 JpaExecutionRecorder（id 生成改 snowflake）**

构造器加 `SnowflakeIdGenerator snowflake`。`recordSuccess`：`po.id = UUID.randomUUID().toString()` → `po.id = snowflake.next()`。`recordFailure` 同理。`executionId` 字段引用改 Long。
在 `AlertingPipelineConfig.executionRecorder(...)` bean 注入 `SnowflakeIdGenerator`。

- [ ] **Step 4: 改运行态 Pipeline + Subscription 领域 record**

`observe-pipeline/domain/Pipeline.java`：`id` String→Long；`version` 不变（int）。
`observe-pipeline/domain/subscription/Subscription.java`：`id`/`pipelineId` String→Long。`SourceRef` 不含 id，不变。

- [ ] **Step 5: 改 PipelineRegistry + Snapshot + Matcher**

`PipelineRegistry.Snapshot`：`Map<String, Pipeline> pipelinesById` → `Map<Long, Pipeline>`；`pipelineById(Long)`；`dbTableKey` 签名不变（db/table 仍是 String）。
`DefaultSubscriptionMatcher.match`：`sub.pipelineId()` 已 Long；返回的 `MatchedSubscription` 中 subscription.id/pipelineId Long。
`SubscriptionMatcher.MatchedSubscription`：id 字段对齐。

- [ ] **Step 6: 改延时层（subscriptionId Long）**

`InMemoryDelayedEventStore`：内部用 subscriptionId 的地方 String→Long（若有 EventPaths 抽 correlationKey 不涉及 sub id 则跳过）。`DelayedActionHandler`：handle 签名若含 subscriptionId 则改 Long。

- [ ] **Step 7: 改测试 fixture（EndToEndFlowTest + EngineSmokeTest）**

`EndToEndFlowTest`：建 pipeline/subscription 时 id 用 Long（或由 service 自动分配）；event fixture 不含 pipeline id 直接引用则不变。逐处编译错误修。
`EngineSmokeTest`：`Pipeline("smoke-pipeline", ...)` 第一个参数 String→Long（用固定 long 如 `1L`，测试无需真实 snowflake）。

- [ ] **Step 8: 编译 pipeline + bootstrap**

Run: `mvn -q -pl observe-bootstrap -am compile`
Expected: kernel + config + pipeline + bootstrap 编译通过（controlplane 仍可能失败，Task 6 修）。

- [ ] **Step 9: 提交**

```bash
git add observe-pipeline/ observe-bootstrap/
git commit -m "$(cat <<'EOF'
refactor(pipeline): switch execution/subscription/pipeline ids to BIGINT

ExecutionPo/FailedExecutionPo id 及跨表引用列 String→Long；JpaExecutionRecorder 用 SnowflakeIdGenerator 分配 id；运行态 Pipeline/Subscription/PipelineRegistry 的 id String→Long，对齐 config 模块（ADR-0003）。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 改 alerting 模块 PO/Repository/Mapper/领域 record 的 id（alerts + alerts_evidence）

**Files:**
- Modify: `observe-alerting/.../AlertPo.java`（`id` String→Long；`executionId` String→Long）
- Modify: `observe-alerting/.../EvidencePo.java`（`alertId` String→Long；PK；B5 再调 1:N 结构）
- Modify: `observe-alerting/.../AlertRepository.java`、`EvidenceRepository.java`（泛型 + 方法签名 String→Long）
- Modify: `observe-alerting/.../AlertMapper.java`、`EvidenceMapper.java`（id 转换 String→Long）
- Modify: `observe-alerting/domain/AlertEntity.java`、`EvidenceEntity.java`（id 字段 String→Long）
- Modify: `observe-alerting/infrastructure/DefaultAlertSink.java`（id 生成从 `UUID.randomUUID().toString()` 改 `snowflake.next()`；构造器注入 SnowflakeIdGenerator）
- Modify: `observe-alerting/infrastructure/AlertResolveJob.java`（`findExpiredFiringIds` 返回 List<String>→List<Long>；`resolveBatch(List<Long>)`）
- Modify: `observe-bootstrap/.../config/AlertingPipelineConfig.java`（DefaultAlertSink bean 注入 SnowflakeIdGenerator）
- Modify: `observe-alerting/src/test/.../DefaultAlertSinkIntegrationTest.java`、`TestExecutionContext.java`（fixture id Long）

**Interfaces:**
- Consumes: Task 1/2 `SnowflakeIdGenerator`。
- Produces: alerting 模块所有 id 改 Long。

- [ ] **Step 1: 改 AlertPo + EvidencePo**

`AlertPo.java`：`id` String→Long（去 length）；`executionId` String→Long。
`EvidencePo.java`：`alertId` String→Long（PK，1:1 暂保持 alertId 作 PK；B5 改独立 id + alertId 引用列）。

- [ ] **Step 2: 改 Repository + Mapper**

`AlertRepository`：泛型 `<AlertPo, Long>`；`updateEmit(Long id, ...)`；`resolveBatch(List<Long> ids, ...)`；`findExpiredFiringIds` 返回 `List<Long>`。
`EvidenceRepository`：泛型 `<EvidencePo, Long>`；`findByAlertId(Long)`。
`AlertMapper`/`EvidenceMapper`：id String↔Long 转换。

- [ ] **Step 3: 改领域 record**

`AlertEntity`：`id`/`executionId` String→Long。
`EvidenceEntity`：`alertId` String→Long。

- [ ] **Step 4: 改 DefaultAlertSink（id 生成改 snowflake）**

构造器加 `SnowflakeIdGenerator snowflake`。`persist`：`UUID.randomUUID().toString()` → `snowflake.next()`。`persistEvidence` 的 alertId 参数 String→Long。
`AlertingPipelineConfig.defaultAlertSink(...)` bean 注入 SnowflakeIdGenerator。

- [ ] **Step 5: 改 AlertResolveJob（Long）**

`resolveExpiredAlerts`：处理 List<Long>。

- [ ] **Step 6: 改测试 fixture**

`DefaultAlertSinkIntegrationTest`：assertions 中 alert id 类型 Long；`TestExecutionContext` 若含 executionId 则 Long。

- [ ] **Step 7: 编译 alerting + bootstrap**

Run: `mvn -q -pl observe-alerting -am compile`
Expected: 通过（controlplane 仍可能失败，Task 6 修）。

- [ ] **Step 8: 提交**

```bash
git add observe-alerting/ observe-bootstrap/
git commit -m "$(cat <<'EOF'
refactor(alerting): switch alert/evidence ids to BIGINT

AlertPo/EvidencePo id 及跨表引用列 String→Long；DefaultAlertSink 用 SnowflakeIdGenerator 分配 id；AlertEntity/EvidenceEntity/AlertResolveJob id String→Long（ADR-0003）。evidence 1:N 结构留给 B5。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 改 controlplane DTO/Controller（id String→Long），全量编译绿

**Files:**
- Modify: `observe-controlplane/.../dto/PipelineDto.java`、`VersionDto.java`、`SubscriptionDto.java`、`AlertDto.java`（若有）、`ExecutionDto.java`、`FailedExecutionDto.java`（id 字段 String→Long）
- Modify: `observe-controlplane/.../PipelineController.java`、`SubscriptionController.java`、`AlertController.java`（若有）、`ExecutionController.java`（`@PathVariable String id`→`Long id`；service 调用入参 Long）
- Modify: `observe-controlplane/.../EventController.java`（若有 id 引用）

**Interfaces:**
- Consumes: Task 3-5 所有模块 id 已改 Long。
- Produces: controlplane 对齐，全量编译通过。

- [ ] **Step 1: 改所有 DTO 的 id 字段 String→Long**

逐个 DTO：`String id` → `Long id`；`String pipelineId` → `Long pipelineId`；`String executionId` → `Long executionId` 等（按字段语义）。

- [ ] **Step 2: 改所有 Controller 的 @PathVariable + service 调用**

`PipelineController`：`@PathVariable String id` → `Long id`；`crud.find/update/archive(id)` 入参 Long；`CreatePipelineRequest.id` 字段 String→Long（或移除，由 service snowflake 分配——**本份保持 request 带 id 但类型改 Long，分配逻辑 Task 3 已在 service 内**；若 request 不再传 id 则去掉字段。**推荐**：request 去掉 id 字段，id 完全由 service 内部 snowflake 分配，符合 ADR-0003「id 不对外暴露」精神；但本份先保类型一致，B2b 引入 namespace+name 寻址时再决定对外形态）。

> 决策（本份内执行）：request DTO 去掉 `id` 字段，create 时 service 内部分配；`@PathVariable` 用 Long（B2b 再换成 namespace+name）。

- [ ] **Step 3: 全量编译**

Run: `mvn -q clean compile`
Expected: BUILD SUCCESS（所有模块编译通过）。

- [ ] **Step 4: 提交**

```bash
git add observe-controlplane/
git commit -m "$(cat <<'EOF'
refactor(controlplane): align ids to BIGINT, drop request id field

DTO/Controller id 字段 String→Long；create 请求去掉 id 字段（由 service snowflake 内部分配，对齐 ADR-0003）。namespace+name 寻址留给 B2b。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 同步 SQL 参考脚本 + 全量测试绿（B2a 收尾）

**Files:**
- Modify: `observe-config/src/main/resources/db/migration/config/V1__init.sql`
- Modify: `observe-alerting/src/main/resources/db/migration/alerting/V1__init.sql`
- Modify: `observe-pipeline/src/main/resources/db/migration/pipeline/V1__init.sql`

**Interfaces:**
- Consumes: Task 1-6 全部完成。
- Produces: SQL 参考脚本与实体一致；B2a 全量测试绿。

> SQL 脚本不被 Flyway 执行（未接入），但保持真实以备未来真实 DB 部署。

- [ ] **Step 1: 更新 config V1__init.sql**

`pipelines.id VARCHAR(64) PRIMARY KEY` → `BIGINT PRIMARY KEY`。
`pipeline_versions.pipeline_id VARCHAR(64)` → `BIGINT`。
`subscriptions.id VARCHAR(64)` + `pipeline_id VARCHAR(64)` → `BIGINT`。

- [ ] **Step 2: 更新 alerting V1__init.sql**

`alerts.id VARCHAR(36)` + `execution_id` → `BIGINT`。
`alerts_evidence.alert_id VARCHAR(36)` → `BIGINT`。

- [ ] **Step 3: 更新 pipeline V1__init.sql**

`executions.id VARCHAR(36)` + `subscription_id` + `pipeline_id` → `BIGINT`。
`failed_executions.id` + `execution_id` + `subscription_id` + `pipeline_id` → `BIGINT`。

- [ ] **Step 4: 全量测试**

Run: `mvn clean test`
Expected: BUILD SUCCESS，全部模块测试绿。

> 若有测试因 id 类型变更失败（如断言 `id instanceof String`），修测试 fixture。SnowflakeIdGeneratorTest、EngineSmokeTest、EndToEndFlowTest、DefaultAlertSinkIntegrationTest、JpaExecutionRecorderTest 应全绿。

- [ ] **Step 5: 提交 + 收尾**

```bash
git add observe-*/src/main/resources/db/migration/
git commit -m "$(cat <<'EOF'
chore(db): align SQL reference scripts to BIGINT ids (ADR-0003)

同步 V1__init.sql 参考脚本（Flyway 未接入，脚本仅作文档；H2 ddl-auto:update 按实体生成）。B2a 收尾：全量 mvn clean test 绿。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review（计划作者已执行）

1. **Spec 覆盖**：B2 spec §5.2 B2.2「id 改 snowflake BIGINT」= Task 1-7。
   - SnowflakeIdGenerator → Task 1 ✓
   - 所有表 PK VARCHAR→BIGINT → Task 3/4/5 ✓
   - 跨表引用列同步 → Task 3/4/5 ✓
   - 对外 API（本份先保 Long，namespace+name 留 B2b）→ Task 6 ✓（明确标注 B2b 接续）
   - SQL 参考脚本 → Task 7 ✓
   - snowflake workerId=1 硬编码 → Global Constraints + Task 1/2 ✓
   - **不在本份**：namespace 列（B2b）、命名重构（B2c）、延时端口（B2d）、Event sealed（B3）、业务键 (namespace,name)（B2b）—— 均明确标注留给后续份。
2. **占位扫描**：无 TBD/TODO；每个 step 有具体文件 + 代码或命令。✓
3. **类型一致性**：`SnowflakeIdGenerator.next()` 返回 `long`，全计划统一用 `Long`（PO 字段装箱）。`PipelineVersionPk.pipelineId` Long 与 `PipelineVersionPo.pipelineId` Long 一致 ✓。
4. **顺序合理性**：Task 1（生成器）→ Task 2（装配 bean）→ Task 3-5（按模块改 PO/Repository/Mapper/领域，跨模块编译可暂断，符合用户「批末正确即可」）→ Task 6（controlplane 对齐，全量编译绿）→ Task 7（SQL + 全量测试绿收尾）✓。
5. **风险标注**：Task 3/4/5 跨模块编译断裂是预期的（用户已确认 B2a 内部不强求每提交编译通过）。Task 6 收尾全量编译绿，Task 7 全量测试绿。
