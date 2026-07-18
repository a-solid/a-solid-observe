# B2b 标识层地基（namespace + CRUD API）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入 namespace 顶层隔离（ADR-0002）：新增 `namespaces` 表 + CRUD API；所有资源表加 `namespace` 列；资源寻址从 `/{id}`（Long）改为 `/namespaces/{ns}/<resource>/{name}`（业务键）；应用层软隔离铁律（所有读写带 namespace 过滤）。

**Architecture:** namespace 是显式资源（`namespaces` 表，CRUD + metadata）。资源表（pipelines/pipeline_versions/subscriptions/alerts/alerts_evidence/executions/failed_executions）加 `namespace` 列（NOT NULL，资源表自身；alerts/executions/failed_executions 从触发 pipeline denormalize）。pipelines/subscriptions 加业务键唯一约束 `(namespace, name)`。对外 API 全部改用 `/api/v1/namespaces/{ns}/...` 形态，`name` 是 namespace 内业务名；BIGINT id 不对外暴露。Repository 查询方法签名加 namespace 参数（软隔离铁律）。

**Tech Stack:** Java 17、Spring Boot、JPA/Hibernate、H2（开发，`ddl-auto:update`）、JUnit 5、AssertJ。

> **本子计划是 B2 的第 2 份（共 4 份）**。前置 B2a（snowflake BIGINT id，已合并 master `b44a915`）。后续 B2c（命名重构 SubscriptionDefinition）→ B2d（延时端口）。本份不重命名 Subscription（B2c）、不改 Event（B3）。

## Global Constraints

- 设计权威：`CONTEXT.md` Namespacing & Identity 节 + `docs/adr/0002-namespace-top-level-isolation.md`。
- namespace 职责：(a) 名字作用域 + (b) 可见性边界。环境隔离不用 namespace（靠 profile/多实例）。RBAC 远期不做。
- 业务键 `(namespace, name)`：pipelines/subscriptions 加唯一约束；对外 API 用业务键寻址；BIGINT id 不对外。
- **软隔离铁律**：应用层所有读写必带 namespace 过滤，DB 无强制。漏带即串数据 bug。Repository 查询方法签名加 namespace 参数。
- namespace 是显式资源：`namespaces` 表 + CRUD API（`POST/GET/PUT/DELETE /api/v1/namespaces`）。
- H2 in-memory + `ddl-auto:update`：加列无迁移问题；SQL 参考脚本同步更新（Task 末）。
- 每批结束（B2b 全部任务完成）必须 `mvn compile` + `mvn test` 全绿。B2b 内部不强求每提交编译通过，批末正确即可（但 namespace 是加法不是类型变更，多数步骤可保持编译）。
- 不使用 FK；引用完整性靠应用层。

## 关键设计决策（本份内执行）

1. **namespace 列类型**：`VARCHAR` NOT NULL（namespace 名是字符串业务键）。`namespaces.id` BIGINT snowflake PK（对齐 B2a）；`namespaces.name` VARCHAR 唯一约束。
2. **资源表 namespace 列**：所有资源表加 `namespace VARCHAR NOT NULL`。pipelines/subscriptions 加唯一约束 `(namespace, name)`（name 列已存在）。alerts/alerts_evidence/executions/failed_executions 的 namespace 从触发 pipeline denormalize。
3. **寻址改业务键**：controller 路径 `/api/v1/namespaces/{ns}/pipelines/{name}` 等。service 方法 `find(namespace, name)` / `findAll(namespace)` / `update(namespace, name, ...)`。
4. **namespace denormalize 到 alert/execution**：触发 pipeline 的 namespace 在 ExecutionMeta 构造时从 pipeline 取，写入 executions/failed_executions/alerts 的 namespace 列。
5. **PipelineDefinition/Subscription 领域 record 加 namespace + name 字段**（name 现状已有；namespace 新增）。

## File Structure

**Create:**
- `observe-config/domain/Namespace.java` — namespace 领域 record。
- `observe-config/infrastructure/persistence/NamespacePo.java` + `NamespaceRepository.java` + `NamespaceMapper.java`。
- `observe-config/application/NamespaceCrudService.java` — CRUD service。
- `observe-controlplane/interfaces/NamespaceController.java` + `dto/NamespaceDto.java`。
- `observe-config/src/test/.../NamespaceCrudServiceTest.java`。
- `observe-controlplane/src/test/.../NamespaceControllerTest.java`（可选，若 EventControllerTest 模式可复用）。

**Modify（资源表加 namespace + 业务键寻址）:**
- `observe-config/domain/PipelineDefinition.java`（加 namespace）、`Subscription.java`（加 namespace）、`PipelineVersion.java`（加 namespace，denormalize from pipeline）。
- `observe-config/.../PipelineDefinitionPo.java`（加 namespace 列 + name 列已存在 → 加唯一约束语义由 service/SQL 保证）、`SubscriptionPo.java`、`PipelineVersionPo.java`。
- `observe-config/.../PipelineDefinitionMapper`、`SubscriptionMapper`、`PipelineVersionMapper`。
- `observe-config/application/PipelineCrudService`、`SubscriptionCrudService`、`VersionPublishService`（方法加 namespace 参数；find/update/archive/delete 按 (namespace, name)）。
- `observe-config/.../PipelineDefinitionRepository`、`SubscriptionRepository`、`PipelineVersionRepository`（加 `findByNamespaceAndName`、`findAllByNamespace` 等查询方法）。
- `observe-config/application/PipelineRegistryLoader`（load 时带 namespace 透传到运行态）。
- `observe-pipeline/domain/Pipeline.java`、`domain/subscription/Subscription.java`（运行态加 namespace）、`application/PipelineRegistry.Snapshot`（pipelines 索引可仍按 Long id；namespace 作为 pipeline 属性透传）。
- `observe-pipeline/domain/Execution.java`、`FailedExecution.java`（加 namespace）；`infrastructure/persistence/ExecutionPo`、`FailedExecutionPo`（加 namespace 列）；`JpaExecutionRecorder`（从 ExecutionMeta.pipelineNamespace 写入）。
- `observe-kernel/event/model/ExecutionMeta.java`（加 namespace 字段，从 pipeline 取）。
- `observe-alerting/domain/AlertEntity.java`、`EvidenceEntity.java`（加 namespace）；`AlertPo`、`EvidencePo`（加 namespace 列）；`DefaultAlertSink`（从 ExecutionMeta.namespace denormalize）；`AlertQueryService`、`AlertController`、`ExecutionController`（按 namespace 过滤）。
- `observe-controlplane/interfaces/PipelineController`、`SubscriptionController`、`AlertController`、`ExecutionController`（路径改 `/namespaces/{ns}/...`；@PathVariable name）。
- `observe-controlplane/interfaces/dto/*Dto.java`（加/暴露 namespace）。
- `observe-bootstrap/.../EndToEndFlowTest`、`EngineSmokeTest`（fixture 带 namespace）。
- SQL 参考脚本（Task 末同步）。

**接口边界**：
- `Namespace`（config.domain）：`record Namespace(Long id, String name, String displayName, Instant createdAt, Instant updatedAt)`。
- 资源领域 records 加 `String namespace` 字段。
- service 寻址改 `(namespace, name)`。

---

## Task 1: Namespace 领域 + PO + Repository + Mapper + CRUD service + 单测

**Files:**
- Create: `observe-config/domain/Namespace.java`
- Create: `observe-config/infrastructure/persistence/NamespacePo.java`, `NamespaceRepository.java`, `NamespaceMapper.java`
- Create: `observe-config/application/NamespaceCrudService.java`
- Test: `observe-config/src/test/java/com/imsw/observe/config/application/NamespaceCrudServiceTest.java`

**Interfaces:**
- Consumes: B2a `SnowflakeIdGenerator`。
- Produces: `Namespace` record、`NamespacePo`、`NamespaceRepository`、`NamespaceCrudService`（create/find/findByName/findAll/update/delete，用 snowflake 分配 id）。

- [ ] **Step 1: 写 Namespace 领域 record**

```java
package com.imsw.observe.config.domain;

import java.time.Instant;

public record Namespace(Long id, String name, String displayName, Instant createdAt, Instant updatedAt) {}
```

- [ ] **Step 2: 写 NamespacePo**

```java
package com.imsw.observe.config.infrastructure.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "namespaces")
public class NamespacePo {

    @Id
    @Column(name = "id", nullable = false)
    public Long id;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "display_name")
    public String displayName;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
```

- [ ] **Step 3: 写 NamespaceRepository**

```java
package com.imsw.observe.config.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NamespaceRepository extends JpaRepository<NamespacePo, Long> {

    Optional<NamespacePo> findByName(String name);
}
```

- [ ] **Step 4: 写 NamespaceMapper**

```java
package com.imsw.observe.config.infrastructure.persistence;

import com.imsw.observe.config.domain.Namespace;

public final class NamespaceMapper {

    private NamespaceMapper() {}

    public static Namespace toEntity(final NamespacePo po) {
        if (po == null) {
            return null;
        }
        return new Namespace(po.id, po.name, po.displayName, po.createdAt, po.updatedAt);
    }

    public static NamespacePo toPo(final Namespace entity) {
        if (entity == null) {
            return null;
        }
        NamespacePo po = new NamespacePo();
        po.id = entity.id();
        po.name = entity.name();
        po.displayName = entity.displayName();
        po.createdAt = entity.createdAt();
        po.updatedAt = entity.updatedAt();
        return po;
    }
}
```

- [ ] **Step 5: 写 NamespaceCrudService**

```java
package com.imsw.observe.config.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.config.domain.Namespace;
import com.imsw.observe.config.infrastructure.persistence.NamespaceMapper;
import com.imsw.observe.config.infrastructure.persistence.NamespacePo;
import com.imsw.observe.config.infrastructure.persistence.NamespaceRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

@Service
public class NamespaceCrudService {

    private final NamespaceRepository repository;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public NamespaceCrudService(final NamespaceRepository repository, final SnowflakeIdGenerator snowflakeIdGenerator) {
        this.repository = repository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Transactional
    public Namespace create(final String name, final String displayName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("namespace name must not be blank");
        }
        if (repository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("namespace already exists: " + name);
        }
        NamespacePo po = new NamespacePo();
        po.id = snowflakeIdGenerator.next();
        po.name = name;
        po.displayName = displayName;
        Instant now = Instant.now();
        po.createdAt = now;
        po.updatedAt = now;
        return NamespaceMapper.toEntity(repository.save(po));
    }

    @Transactional(readOnly = true)
    public Namespace findByName(final String name) {
        return repository.findByName(name).map(NamespaceMapper::toEntity).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Namespace> findAll() {
        return repository.findAll().stream().map(NamespaceMapper::toEntity).toList();
    }

    @Transactional
    public Namespace updateDisplayName(final String name, final String displayName) {
        NamespacePo po = repository
                .findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("namespace not found: " + name));
        po.displayName = displayName;
        po.updatedAt = Instant.now();
        return NamespaceMapper.toEntity(repository.save(po));
    }

    @Transactional
    public void delete(final String name) {
        NamespacePo po = repository
                .findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("namespace not found: " + name));
        repository.delete(po);
    }
}
```

- [ ] **Step 6: 写 NamespaceCrudServiceTest**

```java
package com.imsw.observe.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.imsw.observe.config.infrastructure.persistence.NamespaceRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

class NamespaceCrudServiceTest {

    private final NamespaceRepository repository = TestJpaFactory.namespaceRepository();
    private final NamespaceCrudService service =
            new NamespaceCrudService(repository, new SnowflakeIdGenerator(1L, 0L));

    @Test
    void createAssignsSnowflakeIdAndPersists() {
        Namespace ns = service.create("payments", "Payments Team");
        assertThat(ns.id()).isPositive();
        assertThat(ns.name()).isEqualTo("payments");
        assertThat(service.findByName("payments")).isNotNull();
    }

    @Test
    void createRejectsDuplicateName() {
        service.create("payments", "Payments");
        assertThatThrownBy(() -> service.create("payments", "Other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace already exists");
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create("  ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateDisplayNameKeepsNameAndId() {
        Namespace ns = service.create("payments", "Old");
        Namespace updated = service.updateDisplayName("payments", "New");
        assertThat(updated.displayName()).isEqualTo("New");
        assertThat(updated.name()).isEqualTo("payments");
        assertThat(updated.id()).isEqualTo(ns.id());
    }

    @Test
    void deleteRemovesNamespace() {
        service.create("payments", "P");
        service.delete("payments");
        assertThat(service.findByName("payments")).isNull();
    }
}
```

> 注：`TestJpaFactory.namespaceRepository()` 若 config 模块没有类似 `observe-pipeline` 的 `TestJpaFactory`，则参考 pipeline 模块的实现建一个 config 版（用 `@DataJpaTest` 或内嵌 H2 EntityManager 拼 Repository）。若建内嵌 H2 测试基建成本高，改为 `@SpringBootTest` 风格（参考 `DefaultAlertSinkIntegrationTest`）。implementer 按模块现有测试模式选最省事的。

- [ ] **Step 7: 编译 + 跑测试**

Run: `mvn -q -pl observe-config -am test -Dtest=NamespaceCrudServiceTest`
Expected: 5 个测试全绿（若测试基建需要调整，适配之）。

- [ ] **Step 8: 提交**

```bash
git add observe-config/src/main/java/com/imsw/observe/config/domain/Namespace.java \
        observe-config/src/main/java/com/imsw/observe/config/infrastructure/persistence/Namespace*.java \
        observe-config/src/main/java/com/imsw/observe/config/application/NamespaceCrudService.java \
        observe-config/src/test/java/com/imsw/observe/config/application/NamespaceCrudServiceTest.java
git commit -m "$(cat <<'EOF'
feat(config): add Namespace domain + CRUD service (ADR-0002)

namespaces 表 + 领域 record/PO/Repository/Mapper/CrudService。id 用 snowflake（B2a），name 唯一。后续任务把 namespace 透传到资源表与 API。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Namespace CRUD API（controller + dto）

**Files:**
- Create: `observe-controlplane/interfaces/NamespaceController.java`
- Create: `observe-controlplane/interfaces/dto/NamespaceDto.java`
- Test (optional): `observe-controlplane/src/test/.../NamespaceControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `NamespaceCrudService`。
- Produces: REST API `POST/GET /api/v1/namespaces`, `GET/PUT/DELETE /api/v1/namespaces/{name}`。

- [ ] **Step 1: 写 NamespaceDto**

```java
package com.imsw.observe.controlplane.interfaces.dto;

import com.imsw.observe.config.domain.Namespace;

public record NamespaceDto(Long id, String name, String displayName) {

    public static NamespaceDto from(final Namespace ns) {
        return ns == null ? null : new NamespaceDto(ns.id(), ns.name(), ns.displayName());
    }
}
```

- [ ] **Step 2: 写 NamespaceController**

```java
package com.imsw.observe.controlplane.interfaces;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.config.application.NamespaceCrudService;
import com.imsw.observe.controlplane.interfaces.dto.NamespaceDto;

@RestController
@RequestMapping("/api/v1/namespaces")
public class NamespaceController {

    private final NamespaceCrudService service;

    public NamespaceController(final NamespaceCrudService service) {
        this.service = service;
    }

    @PostMapping
    public NamespaceDto create(@RequestBody final CreateNamespaceRequest req) {
        return NamespaceDto.from(service.create(req.name(), req.displayName()));
    }

    @GetMapping
    public List<NamespaceDto> list() {
        return service.findAll().stream().map(NamespaceDto::from).toList();
    }

    @GetMapping("/{name}")
    public ResponseEntity<NamespaceDto> get(@PathVariable final String name) {
        NamespaceDto dto = NamespaceDto.from(service.findByName(name));
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    @PutMapping("/{name}")
    public NamespaceDto update(@PathVariable final String name, @RequestBody final UpdateNamespaceRequest req) {
        return NamespaceDto.from(service.updateDisplayName(name, req.displayName()));
    }

    @DeleteMapping("/{name}")
    public void delete(@PathVariable final String name) {
        service.delete(name);
    }

    public record CreateNamespaceRequest(String name, String displayName) {}

    public record UpdateNamespaceRequest(String displayName) {}
}
```

- [ ] **Step 3: 编译 + 全量测试（确保 controller 注册不破坏现有）**

Run: `mvn -q -pl observe-controlplane -am compile`
Expected: BUILD SUCCESS。可选：加一个轻量 `NamespaceControllerTest`（参考 `EventControllerTest` 用 MockMvc 或 `@SpringBootTest`）。

- [ ] **Step 4: 提交**

```bash
git add observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/NamespaceController.java \
        observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/dto/NamespaceDto.java
git commit -m "$(cat <<'EOF'
feat(controlplane): add Namespace CRUD API (ADR-0002)

POST/GET /api/v1/namespaces, GET/PUT/DELETE /api/v1/namespaces/{name}

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 资源表加 namespace 列（PO + 领域 record + Mapper）

**Files:**
- Modify: `PipelineDefinition.java`（+namespace）、`Subscription.java`（+namespace）、`PipelineVersion.java`（+namespace）
- Modify: `PipelineDefinitionPo.java`、`SubscriptionPo.java`、`PipelineVersionPo.java`（+namespace 列 NOT NULL）
- Modify: `PipelineDefinitionMapper`、`SubscriptionMapper`、`PipelineVersionMapper`
- Modify: `ExecutionPo.java`、`FailedExecutionPo.java`（+namespace 列 NOT NULL）、`Execution.java`/`FailedExecution.java`（+namespace）
- Modify: `AlertPo.java`、`EvidencePo.java`（+namespace 列 NOT NULL）、`AlertEntity.java`/`EvidenceEntity.java`（+namespace）
- Modify: `ExecutionMeta.java`（kernel，+namespace 字段）

**Interfaces:**
- Consumes: Task 1。
- Produces: 所有领域/PO 带 namespace 字段。

- [ ] **Step 1: config 领域 + PO + Mapper 加 namespace**

`PipelineDefinition` record 加 `String namespace`（建议放 id 后、team 前，作第一分类维度）。
`Subscription` record 加 `String namespace`。
`PipelineVersion` record 加 `String namespace`（denormalize from pipeline）。
对应 PO 加 `@Column(name="namespace", nullable=false) public String namespace;`。
Mapper 透传 namespace。

- [ ] **Step 2: pipeline Execution/FailedExecution PO + record 加 namespace**

`ExecutionPo`/`FailedExecutionPo` 加 `namespace` 列 NOT NULL。
`Execution`/`FailedExecution` record 加 `namespace`。

- [ ] **Step 3: alerting Alert/Evidence PO + record 加 namespace**

`AlertPo`/`EvidencePo` 加 `namespace` 列 NOT NULL。
`AlertEntity`/`EvidenceEntity` 加 `namespace`。

- [ ] **Step 4: kernel ExecutionMeta 加 namespace**

`ExecutionMeta` record 加 `String namespace`（从 pipeline 取，用于 denormalize 到 execution/alert）。

- [ ] **Step 5: 编译（预期多处未填 namespace 的构造调用失败，记录，后续 Task 4/5/6 修）**

Run: `mvn -q -pl observe-bootstrap -am compile`
Expected: 跨模块编译断（record 加字段后所有构造点要补 namespace），后续 service/recorder/sink/controller 修。

- [ ] **Step 6: 提交**

```bash
git add observe-config/ observe-pipeline/ observe-alerting/ observe-kernel/
git commit -m "$(cat <<'EOF'
refactor: add namespace column to all resource tables/records (ADR-0002)

pipelines/pipeline_versions/subscriptions/alerts/alerts_evidence/executions/failed_executions + 领域 record + ExecutionMeta 加 namespace 字段。软隔离地基。service/recorder/sink/controller 的 namespace 透传在后续任务。

NOTE: 跨模块编译暂断（record 加字段），批末统一绿。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: service/loader 透传 namespace + 业务键寻址（config + pipeline 运行态 + kernel meta 构造）

**Files:**
- Modify: `PipelineCrudService`、`SubscriptionCrudService`、`VersionPublishService`（方法加 namespace 参数；find/update/archive/delete 按 (namespace, name)）
- Modify: `PipelineDefinitionRepository`、`SubscriptionRepository`、`PipelineVersionRepository`（加 `findByNamespaceAndName`、`existsByNamespaceAndName`、`findAllByNamespace`）
- Modify: `PipelineRegistryLoader`（load 时 namespace 透传到运行态 Subscription/Pipeline；ExecutionMeta 构造带 namespace）
- Modify: `DefaultPipelineRunner`（buildMeta 时 namespace 从 pipeline 取，写入 ExecutionMeta.namespace）
- Modify: `JpaExecutionRecorder`（recordSuccess/recordFailure 写入 po.namespace = meta.namespace()）
- Modify: `DefaultAlertSink`（写入 alert.namespace / evidence.namespace = meta.namespace()）
- Modify: pipeline 运行态 `Pipeline`、`Subscription`（加 namespace 字段，loader 透传）

**Interfaces:**
- Consumes: Task 3（record 带 namespace）。
- Produces: service 按 (namespace,name) 寻址；namespace 透传到 execution/alert denormalize。

- [ ] **Step 1: Repository 加 namespace 查询方法**

`PipelineDefinitionRepository`: `Optional<PipelineDefinitionPo> findByNamespaceAndName(String namespace, String name)`、`List<PipelineDefinitionPo> findAllByNamespace(String namespace)`、`boolean existsByNamespaceAndName(String, String)`。
`SubscriptionRepository`: 同款（按 namespace + name；subscription name 列已存在为 `name`）。
`PipelineVersionRepository`: 按 `(namespace, pipelineId)` 或沿用复合键（namespace 从 pipeline denormalize，可加 `findByNamespace` 辅助）。

- [ ] **Step 2: service 方法签名加 namespace（业务键寻址）**

`PipelineCrudService`：
- `create(namespace, name, team, application, labels, description, createdBy)` — 校验 namespace 存在（调 NamespaceCrudService.findByName，不存在抛异常）；po.namespace = namespace。
- `find(namespace, name)`、`findAll(namespace)`、`update(namespace, name, ...)`、`archive(namespace, name)`。
`SubscriptionCrudService`：create/update/find/findAll/delete 加 namespace 参数，按 (namespace, name)。
`VersionPublishService`：saveDraft/publish/archive/versions 按 (namespace, pipelineName)。

- [ ] **Step 3: Loader + 运行态透传 namespace**

运行态 `Pipeline`/`Subscription` 加 `String namespace`。
Loader.toPipelineSubscription / deserialize 时 namespace 从 PO 透传到运行态。
Loader 构建 ExecutionMeta 时（若有）带 namespace；ExecutionMeta 的 namespace 实际在 DefaultPipelineRunner.buildMeta 构造（Step 4）。

- [ ] **Step 4: DefaultPipelineRunner.buildMeta namespace**

`ExecutionMeta` 构造加 `pipeline.namespace()`（运行态 Pipeline 已带 namespace）。

- [ ] **Step 5: JpaExecutionRecorder + DefaultAlertSink 写入 namespace**

`JpaExecutionRecorder.recordSuccess/recordFailure`：`po.namespace = meta.namespace()`。
`DefaultAlertSink.persist`：`entity.namespace = meta.namespace()`（AlertEntity/EvidenceEntity）。

- [ ] **Step 6: 编译 + 跑模块测试**

Run: `mvn -q -pl observe-bootstrap -am compile`
Expected: kernel/config/pipeline/alerting/bootstrap 编译通过（controlplane Task 6 修）。

Run: `mvn -q -pl observe-bootstrap -am test`
Expected: 现有测试可能因 service 签名变化失败，Task 5/6 修测试。本步确认主线编译。

- [ ] **Step 7: 提交**

```bash
git add observe-config/ observe-pipeline/ observe-alerting/ observe-bootstrap/
git commit -m "$(cat <<'EOF'
refactor: thread namespace through services/loader/runner/recorder/sink (ADR-0002)

service 按 (namespace, name) 业务键寻址；Loader/运行态 Pipeline/Subscription 透传 namespace；ExecutionMeta.namespace 从 pipeline 取；execution/alert namespace denormalize。软隔离铁律：所有 Repository 查询带 namespace。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: controlplane 改业务键寻址 + DTO namespace + AlertQueryService/ExecutionController namespace 过滤

**Files:**
- Modify: `PipelineController`、`SubscriptionController`（路径 `/api/v1/namespaces/{ns}/pipelines/{name}` 等；@PathVariable ns+name；调 service(namespace,name)）
- Modify: `AlertController`、`ExecutionController`（查询加 namespace 过滤；路径可选改 `/namespaces/{ns}/...` 或保留顶层带 `?namespace=` 参数——**推荐** alerts/executions 查询用 `?namespace=` 参数 + 顶层路径，单条按 namespace+id）
- Modify: `AlertQueryService`、`ExecutionQueryService`（方法加 namespace 参数，查询 WHERE namespace=?）
- Modify: 所有 DTO 加 namespace 字段（响应暴露 namespace）
- Modify: `VersionDto`、`AlertDto`、`EvidenceDto`、`ExecutionDto`、`FailedExecutionDto`、`SubscriptionDto`、`PipelineDto`

**Interfaces:**
- Consumes: Task 4。
- Produces: 对外 API 业务键寻址 + namespace 过滤。

- [ ] **Step 1: Controller 路径改业务键**

`PipelineController` `@RequestMapping("/api/v1")` + 方法 `@PostMapping("/namespaces/{namespace}/pipelines")`、`@GetMapping("/namespaces/{namespace}/pipelines")`、`@GetMapping("/namespaces/{namespace}/pipelines/{name}")` 等。`@PathVariable String namespace, @PathVariable String name`。
`SubscriptionController` 同款（`/namespaces/{namespace}/subscriptions` + `/{name}`）。
版本路径 `/namespaces/{namespace}/pipelines/{name}/versions` 等。

- [ ] **Step 2: AlertController/ExecutionController namespace 过滤**

列表查询加 `@RequestParam String namespace`（必填，软隔离铁律），传给 AlertQueryService/ExecutionQueryService。
单条查询按 (namespace, id)。

- [ ] **Step 3: AlertQueryService/ExecutionQueryService 加 namespace**

方法签名加 namespace，查询 `WHERE namespace = :ns`。

- [ ] **Step 4: DTO 加 namespace 字段**

所有 DTO record 加 `String namespace`，from() 透传。

- [ ] **Step 5: 全量编译**

Run: `mvn clean compile`
Expected: BUILD SUCCESS（所有模块）。

- [ ] **Step 6: 提交**

```bash
git add observe-controlplane/
git commit -m "$(cat <<'EOF'
refactor(controlplane): business-key addressing + namespace filtering (ADR-0002/0003)

pipelines/subscriptions 改 /api/v1/namespaces/{ns}/.../{name} 业务键寻址；alerts/executions 查询带 ?namespace= 过滤（软隔离铁律）；DTO 暴露 namespace。BIGINT id 不对外。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 修测试 fixture（EndToEndFlowTest/EngineSmokeTest 等）带 namespace

**Files:**
- Modify: `EndToEndFlowTest`（先建 namespace → 建 pipeline 带 namespace → 触发 → 校验 alert.namespace）
- Modify: `EngineSmokeTest`（Pipeline fixture 加 namespace）
- Modify: 其他因签名变化失败的测试（`JpaExecutionRecorderTest`、`DefaultAlertSinkIntegrationTest`、`DefaultSubscriptionMatcherTest`、`SourceDispatcherTest`、`InMemoryDelayedEventStoreTest`、`ConditionTest`、`PipelineCrudServiceTest` 等，按需）

**Interfaces:**
- Consumes: Task 4/5。
- Produces: 全量测试绿。

- [ ] **Step 1: EndToEndFlowTest 加 namespace 流程**

测试 setup：先 `namespaceCrudService.create("e2e", ...)` → 建 pipeline 带 namespace="e2e" → 触发 → 断言落库 alert.namespace="e2e"。

- [ ] **Step 2: EngineSmokeTest Pipeline fixture 加 namespace**

`Pipeline(...)` 构造加 namespace 参数（如 "smoke"）。

- [ ] **Step 3: 其他测试适配**

逐个跑 `mvn -q -pl <module> test`，按编译/断言错误修 fixture（多数是构造器加 namespace 参数 + service 调用加 namespace 参数）。

- [ ] **Step 4: 全量测试**

Run: `mvn clean test`
Expected: BUILD SUCCESS，全绿。

- [ ] **Step 5: 提交**

```bash
git add observe-bootstrap/src/test/ observe-config/src/test/ observe-pipeline/src/test/ observe-alerting/src/test/
git commit -m "$(cat <<'EOF'
test: adapt fixtures to namespace (create namespace → namespace-scoped resources)

EndToEndFlowTest 先建 namespace 再建 namespace-scoped pipeline；其他测试 fixture 加 namespace 参数。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: SQL 参考脚本同步 + 全量测试绿（B2b 收尾）

**Files:**
- Modify: `observe-config/src/main/resources/db/migration/config/V1__init.sql`（建 namespaces 表；资源表加 namespace 列 + 唯一约束 (namespace, name)）
- Modify: `observe-alerting/src/main/resources/db/migration/alerting/V1__init.sql`（alerts/alerts_evidence 加 namespace 列 + 索引）
- Modify: `observe-pipeline/src/main/resources/db/migration/pipeline/V1__init.sql`（executions/failed_executions 加 namespace 列 + 索引）

**Interfaces:**
- Consumes: Task 1-6。
- Produces: SQL 同步 + B2b 全量绿。

- [ ] **Step 1: config SQL 加 namespaces 表 + 资源表 namespace**

```sql
CREATE TABLE namespaces (
    id BIGINT PRIMARY KEY,
    name VARCHAR NOT NULL,
    display_name VARCHAR,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX idx_namespaces_name ON namespaces(name);
```
`pipelines` 加 `namespace VARCHAR NOT NULL`（在 id 之后）+ `CREATE UNIQUE INDEX idx_pipelines_ns_name ON pipelines(namespace, name);`。`subscriptions` 同款。`pipeline_versions` 加 `namespace VARCHAR NOT NULL`。

- [ ] **Step 2: alerting SQL 加 namespace**

`alerts`/`alerts_evidence` 加 `namespace VARCHAR NOT NULL` + 索引 `(namespace, ...)`。

- [ ] **Step 3: pipeline SQL 加 namespace**

`executions`/`failed_executions` 加 `namespace VARCHAR NOT NULL` + 索引 `(namespace, ...)`。

- [ ] **Step 4: 全量测试（B2b gate）**

Run: `mvn clean test`
Expected: BUILD SUCCESS，全绿。

- [ ] **Step 5: 提交 + 收尾**

```bash
git add observe-*/src/main/resources/db/migration/
git commit -m "$(cat <<'EOF'
chore(db): add namespaces table + namespace columns to SQL reference (ADR-0002)

B2b 收尾：全量 mvn clean test 绿。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review（计划作者已执行）

1. **Spec 覆盖**：B2 spec §5.2 B2.1（namespace 引入）+ B2.2 业务键部分 + namespace CRUD API。
   - namespaces 表 + CRUD → Task 1/2 ✓
   - 资源表加 namespace 列 → Task 3 ✓
   - namespace denormalize 到 alert/execution → Task 4 ✓
   - 软隔离铁律（查询带 namespace）→ Task 4/5 ✓
   - 业务键寻址 `/namespaces/{ns}/.../{name}` → Task 5 ✓
   - SQL 同步 → Task 7 ✓
   - **不在本份**：命名重构 SubscriptionDefinition（B2c）、延时端口（B2d）、Event sealed（B3）、labels 投影列（B5）。
2. **占位扫描**：无 TBD/TODO；Task 1/2 有完整代码，Task 3-7 是改动指引（明确文件 + 改什么），符合 writing-plans 对"改动型 task"的要求。✓
3. **类型一致性**：namespace 一律 `String`（业务键），namespaces.id 是 Long（snowflake）。业务键 `(namespace, name)` 两列都是 String。✓
4. **顺序合理性**：Task 1/2（namespace 自身）→ Task 3（资源表加列，跨模块暂断）→ Task 4（service/loader 透传，编译恢复）→ Task 5（controlplane 寻址）→ Task 6（测试）→ Task 7（SQL+收尾）✓。
5. **风险标注**：Task 3 record 加字段会跨模块断编译（用户已确认批末绿即可）。Task 4 是恢复点。软隔离铁律需在 Task 4/5 严格执行（每个查询带 namespace）。
