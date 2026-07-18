# B7 ADR-0005 全套落地设计（告警 wave + 1:N evidence + 4 态 + ack/resolve + silence）

**日期**：2026-07-19
**状态**：Proposed
**批次**：B7（告警体系大改，schema 变更）
**前置依赖**：**B5**（`ApiResponse`/`ErrorCode`/`ErrorResponseException`/MockMvc 测试模式）；**B6**（alert stats 时间范围查询，silence 命中统计可复用）。B7 ack/resolve 写接口的业务冲突用 B5 的 `ErrorResponseException(CONFLICT, ...)`。
**关联**：`docs/adr/0005-alert-wave-1n-evidence-state-machine.md`（权威）、`docs/2026-07-19-design-and-data-model-review.md` §3（落差来源）、B5/B6 spec

---

## 1. 背景与目标

ADR-0005 状态为 **accepted** 但仅部分实现：evidence 仍是 1:1（PK=`alert_id`）、状态机仍 2 态（FIRING/RESOLVED）、无 `alert_silences` 表、`AlertResolveJob` 未接 `@Scheduled`（FIRING→RESOLVED 实际不生效）、TTL 默认值与 ADR 不符（代码 C60/W30/I15 vs ADR C30/W10/I5）。

**B7 目标**：把 ADR-0005 全文落地——wave 收敛 + 1:N evidence + 4 态状态机 + ack/resolve/ignore 写接口 + silence 规则表与拦截 + AlertResolveJob 定时接线。让「告警能从 FIRING 走到 RESOLVED/ACKNOWLEDGED/IGNORED」整条闭环生效。

**非目标**：
- 不做 wave-frequency cap（每分钟最多 1 evidence，ADR Considered Options，远期）。
- 不做 evidence 归档（§7.1，远期）。
- 不改 Grafana 拉取接口契约（只多状态值，格式不变）。
- 不引入 RBAC（ack/resolve 的 `by` 一期从请求体取，不做鉴权）。

---

## 2. 设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| disposition 列形态 | **显式列 `ack_note`/`ack_by`/`ack_at`**（非 JSON 列） | 可查询、可索引；ADR 留选择权，显式列更利于后续按 ack_by/ack_at 统计 |
| ack/resolve/ignore 端点寻址 | **`?namespace=` query param**（沿用现有 `AlertController` 模式，非 ADR 写的 path `{ns}`） | 与 ADR-0002 软隔离 + 现有 alerts 读端点一致；ADR 的 path 写法是示意，不强制 |
| silence 寻址 | **`?namespace=` query param** 同款 | 一致性；silence 虽是 namespace-scoped 资源，但软隔离铁律统一用 query param |
| silence 匹配存储 | **统一 `match` JSON 列 + `match_type` 枚举** | ADR §3 + 批次设计 §2.7：silence 量小、AlertSink 内存遍历匹配，不按维度建索引 |
| silence 拦截点 | **`DefaultAlertSink.persist` 入口，`findFirst...` 之前**查 silence matcher，命中则 log+return 不建告警 | ADR §3.4：emit 时、落库前拦截 |
| silence 缓存 | **内存缓存 + 短 TTL 刷新**（Caffeine 或 `Map`+定时刷） | ADR §Consequences C2：emit 热路径不能每次查库 |
| AlertResolveJob 触发 | **`@Scheduled(fixedDelay)` 可配置**（默认 60s） | `@EnableScheduling` 已开；`WorkerConfig.HotReloaderScheduler` 是先例 |
| TTL 默认值 | **改 C30/W10/I5**（对齐 ADR，覆盖现 C60/W30/I15） | ADR §1；同步更新设计文档 |
| 1:N evidence 读 | **`getEvidence` 返回 `List`**（200 + 空列表，alert 不存在才 404） | 1:N 后单 alert 多证据 |
| 迁移工具 | **Flyway V2**（沿用 `db/migration/alerting/V*` 约定） | 现有 V1 即 Flyway 风格 |

---

## 3. ADR-0005 条款 → 落地动作（对照表）

| ADR 条款 | 落地动作 |
|---|---|
| W1 wave=ttl | 命名对齐（注释/log 用 wave），无结构改 |
| W2 dedup 也写 evidence | `DefaultAlertSink.persist` dedup 分支补 evidence INSERT（依赖 E1 PK 改完） |
| W3 AlertResolveJob 接 `@Scheduled` | 加 `@Scheduled(fixedDelay=${observe.alerting.resolve-job.interval-millis:60000})` |
| W4 新 wave = 新 FIRING 行 | 现有 `findFirstByFingerprintAndStatusOrderByIdAsc(fp,"FIRING")` 已只匹配 FIRING，行为正确（加测试固化） |
| W5 TTL C30/W10/I5 | `DefaultAlertSink.defaultTtl` 改值 |
| W6 script ttl 覆盖 | 现已支持，无改 |
| E1 evidence PK=snowflake | V2 迁移：加 `id BIGINT PK`，`alert_id` 改普通列 + 索引 |
| E2 alert_id 引用列 | 同 E1 |
| E3 索引 `(alert_id, captured_at)` | V2 加索引 |
| E4 每次 emit 写 evidence | dedup 分支 + 新建分支都 INSERT；加 `emit_seq` |
| E5 evidence 独立于 execution 采样 | 现已独立，无改 |
| S1 4 态 | `AlertStatus` 加 `ACKNOWLEDGED`/`IGNORED`；V2 改 CHECK |
| S2 disposition 列 | V2 加 `ack_note`/`ack_by`/`ack_at` |
| S3 alert_silences 表 | V2 建表（§4.3 DDL） |
| S4 emit 时查 silence 拦截 | `DefaultAlertSink` 注入 `AlertSilenceMatcher` |
| S5 silence ≠ 单条 IGNORED | 两者独立实现 |
| A1-A3 ack/resolve/ignore 端点 | 3 个 POST |
| A4-A6 silence CRUD 端点 | 3 个端点 |
| C2 silence 缓存 | 内存缓存 + TTL |
| C3 silence 全栈 | entity/PO/mapper/repo/service/controller/DTO |

---

## 4. 组件设计

### 4.1 schema 迁移 `V2__alert_wave_state_machine.sql`（observe-alerting）

```sql
-- 4.1.1 alerts: 4 态 + disposition
ALTER TABLE alerts DROP CONSTRAINT ck_alerts_status;
ALTER TABLE alerts ADD CONSTRAINT ck_alerts_status
    CHECK (status IN ('FIRING','RESOLVED','ACKNOWLEDGED','IGNORED'));
ALTER TABLE alerts ADD COLUMN ack_note VARCHAR;
ALTER TABLE alerts ADD COLUMN ack_by VARCHAR;
ALTER TABLE alerts ADD COLUMN ack_at TIMESTAMP;

-- 4.1.2 alerts_evidence: 1:1 → 1:N
ALTER TABLE alerts_evidence DROP PRIMARY KEY;          -- 删 alert_id 上的 PK
ALTER TABLE alerts_evidence ALTER COLUMN alert_id BIGINT NOT NULL;
ALTER TABLE alerts_evidence ADD COLUMN id BIGINT PRIMARY KEY;
ALTER TABLE alerts_evidence ADD COLUMN emit_seq INT;
CREATE INDEX idx_ev_alert_captured ON alerts_evidence(alert_id, captured_at DESC);

-- 4.1.3 alert_silences
CREATE TABLE alert_silences (
    id BIGINT PRIMARY KEY,
    namespace VARCHAR NOT NULL,
    match_type VARCHAR NOT NULL,        -- FINGERPRINT | LABELS | PIPELINE
    match LONG VARCHAR NOT NULL,        -- JSON: {fingerprint?} | {labels?} | {pipelineId?}
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    note VARCHAR,
    created_by VARCHAR NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_silences_match_type CHECK (match_type IN ('FINGERPRINT','LABELS','PIPELINE'))
);
CREATE INDEX idx_silences_ns_ends ON alert_silences(namespace, ends_at);
```
> 注：`spring.jpa.hibernate.ddl-auto: update`（application.yml）开启——需确认 Flyway 是否实际接管（`db/migration/...` 路径强暗示 Flyway）。若 ddl-auto 与 Flyway 并存，V2 以 Flyway 为准；实施时先验证 Flyway bean 装配。H2 in-memory 是唯一现有库，V2 必须在 H2 跑通。

### 4.2 告警侧改造
- `AlertStatus` 加 `ACKNOWLEDGED, IGNORED`。
- `AlertEntity` / `AlertPo` 加 `ackNote`/`ackBy`/`ackAt`；`AlertMapper` 双向拷贝。
- `AlertDto` 暴露这三字段。
- `DefaultAlertSink.defaultTtl` 改 `ofMinutes(30)/ofMinutes(10)/ofMinutes(5)`。
- `DefaultAlertSink.persist`：
  - 入口查 `AlertSilenceMatcher.match(namespace, fingerprint, labels, pipelineId)`，命中 → log + return（不建告警）。
  - dedup 命中分支：`updateEmit` **之后** INSERT 一条 evidence（emit_seq = 当前 alert 的 evidence 数+1，或 repo count）。
- `EvidenceEntity`/`EvidencePo` 加 `Long id`（自有 PK）+ `emitSeq`；`alertId` 降级为普通列。
- `EvidenceRepository`：`findByAlertId`（Optional）→ 保留或弃用，新增 `findAllByAlertIdOrderByCapturedAtAsc(Long)` 返回 `List`。

### 4.3 silence 全栈（新增）
- `AlertSilenceEntity`（domain）：`id, namespace, matchType(枚举 FINGERPRINT/LABELS/PIPELINE), match(Map), startsAt, endsAt, note, createdBy, createdAt, updatedAt`。
- `AlertSilencePo`（`@Table("alert_silences")`）+ `AlertSilenceMapper`（match 用 JSON converter）。
- `AlertSilenceRepository`：`findAllByNamespaceAndEndsAtAfter(namespace, now)`（取当前生效规则）。
- `AlertSilenceMatcher`（infrastructure，注入 `DefaultAlertSink`）：内存缓存活跃规则（key=namespace，TTL 刷新），`match(...)` 按 matchType 评估（FINGERPRINT 精确比、LABELS 子集比、PIPELINE 比 pipelineId）。
- `AlertSilenceService`（application）：CRUD + `activeRules(namespace)` 给 matcher。
- `AlertSilenceController`：`POST/GET/DELETE /api/v1/alert-silences?namespace=`。
- `SilenceDto` + 请求 record（`match` 接收结构化 JSON 或扁平字段，DTO 侧规整成 `match_type`+`match`）。

### 4.4 ack/resolve/ignore 端点
- `AlertController` 加 3 个 POST：
  - `POST /alerts/{id}/ack?namespace=` body `{note}` → ACKNOWLEDGED
  - `POST /alerts/{id}/resolve?namespace=` body `{note}` → RESOLVED
  - `POST /alerts/{id}/ignore?namespace=` body `{note}` → IGNORED
- service：`AlertDispositionService.acknowledge/resolveUser/ignore(namespace, id, note, by)`。
- repo：`@Modifying @Query` 原子更新 status + ack_*（条件 `id=:id and namespace=:ns`，影响 0 行 → `ResourceNotFoundException`）。
- 状态转移守卫：非法转移（如 RESOLVED→ACKNOWLEDGED）抛 `ErrorResponseException(CONFLICT, "非法状态转移 X→Y")`。合法转移图：
  - FIRING → {RESOLVED(system via job), ACKNOWLEDGED, IGNORED, RESOLVED(user)}
  - ACKNOWLEDGED → {RESOLVED}
  - IGNORED → {RESOLVED}
  - RESOLVED → 终态（不再转）

### 4.5 AlertResolveJob 接线
- `AlertResolveJob` 加方法 `@Scheduled(fixedDelayString = "${observe.alerting.resolve-job.interval-millis:60000}")` 委托 `resolveExpiredAlerts()`。
- 配置：`application.yml` 加 `observe.alerting.resolve-job.{interval-millis:60000, batch-size:1000}`；`BATCH_SIZE` 改读配置。

### 4.6 读路径 1:N
- `AlertQueryService.findEvidenceByAlertId` 返回 `Optional` → 改 `List<EvidenceEntity>`（或新方法 `findEvidencesByAlertId`）。
- `AlertController.getEvidence` 返回 `ResponseEntity<EvidenceDto>` → `ApiResponse<List<EvidenceDto>>`（alert 不存在抛 `ResourceNotFoundException`，存在但无 evidence 返回空列表）。
- `EvidenceDto` 加 `id`、`emitSeq`。

---

## 5. 配置（application.yml 新增）

```yaml
observe:
  alerting:
    resolve-job:
      interval-millis: 60000
      batch-size: 1000
    silence:
      cache-ttl-millis: 10000
```
TTL 默认值（C30/W10/I5）保持硬编码（ADR 视为固定默认），不外化。

---

## 6. 测试

### 6.1 schema 迁移测试
- 现有 H2 启动测试（`ObserveApplicationTest`）验证 V2 迁移无错 + 新表/列存在。

### 6.2 告警生命周期单测
- `DefaultAlertSinkTest`（扩展现有 `DefaultAlertSinkIntegrationTest`）：
  - dedup 命中现在也写 evidence（同 alert 多条 evidence，emit_seq 递增）
  - silence 命中不建告警（matcher mock 命中 → 无 alert 行）
  - TTL 默认值 = C30/W10/I5
- `AlertResolveJobTest`：`@Scheduled` 触发（用 `awaitility` 或直接调 `resolveExpiredAlerts`）把过期 FIRING 翻成 RESOLVED，`resolvedAt=endsAt`。
- `AlertDispositionServiceTest`：ack/resolve/ignore 合法转移 + 非法转移抛 CONFLICT + 不存在抛 NOT_FOUND。

### 6.3 silence 单测
- `AlertSilenceMatcherTest`：三种 matchType 匹配/不匹配、缓存命中/过期刷新。
- `AlertSilenceServiceTest`：CRUD + 生效窗口（`ends_at > now`）。

### 6.4 controller MockMvc（复用 B5 MockMvc）
- `POST /alerts/{id}/ack?namespace=` → 200 `{data:{...status:ACKNOWLEDGED, ackBy:...}}`
- 非法转移 → 409 `{error:{code:CONFLICT,...}}`
- 不存在 → 404 `{error:{code:NOT_FOUND,...}}`
- `GET /alerts/{id}/evidence` → `{data:[...多条...]}`
- silence CRUD 三端点

### 6.5 端到端
- `EndToEndFlowTest`（扩展）：emit 同 fingerprint 两次 → 一条 alert + 两条 evidence；wave 过期后 job 翻 RESOLVED；ack 后状态 ACKNOWLEDGED。

---

## 7. 验收标准

1. `mvn compile` + `mvn test` + `mvn checkstyle:check` 全绿
2. V2 迁移在 H2 跑通，新表/列/索引/CHECK 就位
3. `mvn spring-boot:run` 可启动，`AlertResolveJob` 定时触发（日志可见）
4. 手动 curl：
   - emit 两次同 fingerprint → `GET /alerts/{id}/evidence` 返回 2 条
   - `POST /alerts/{id}/ack?namespace=ops -d '{"note":"x"}'` → status=ACKNOWLEDGED
   - `POST /alerts/{id}/ack` 对已 RESOLVED → 409 CONFLICT
   - 建 silence → 再 emit 匹配 fingerprint → 无新 alert
5. 等 wave TTL 过期（或调短配置）→ FIRING 自动转 RESOLVED

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| evidence PK 迁移涉及历史数据 | 一期 H2 in-memory 无历史数据；生产前需在真实库验证 `DROP PRIMARY KEY` + 数据回填 |
| `ddl-auto: update` 与 Flyway 冲突 | 实施先验证 Flyway 装配；必要时关闭 ddl-auto 或设 `validate` |
| silence 缓存与 DB 不一致 | 短 TTL（默认 10s）刷新；CRUD 后主动失效缓存 |
| dedup 分支写 evidence 性能 | wave 内 evidence 量可控（TTL 内同 fingerprint emit 数）；远期加 wave-frequency cap |
| ack/resolve 鉴权 | 一期无 RBAC，`by` 从请求体取；后续接认证后再校验 |
| 状态转移并发 | `@Modifying @Query` 带 `status=:expected` 条件，CAS 式更新，影响 0 行抛 CONFLICT |

---

## 9. 与前后批次关系

- **依赖 B5**：`ErrorResponseException`/`ApiResponse`/MockMvc/`@Valid`。
- **依赖 B6**：silence 命中统计可复用 stats；B6 stats 的 `byStatus` 天然兼容 B7 新增的 ACKNOWLEDGED/IGNORED（不改 B6 代码）。
- **B8**：独立。

---

## 附录：改动文件清单（预估）

**新增（observe-alerting）**：
- `domain/AlertSilenceEntity.java`、`AlertSilenceMatchType.java`
- `infrastructure/persistence/silence/AlertSilencePo.java`、`AlertSilenceRepository.java`、`AlertSilenceMapper.java`
- `infrastructure/AlertSilenceMatcher.java`
- `application/AlertSilenceService.java`、`AlertDispositionService.java`
- `db/migration/alerting/V2__alert_wave_state_machine.sql`

**新增（observe-controlplane）**：
- `interfaces/AlertSilenceController.java`
- `interfaces/dto/SilenceDto.java`、`DispositionRequest.java`

**改造（observe-alerting）**：
- `domain/AlertStatus.java`（+2 态）、`AlertEntity.java`（+ack 字段）、`EvidenceEntity.java`（+id/emitSeq）
- `infrastructure/persistence/alert/AlertPo.java`（+ack 列）、`AlertMapper.java`
- `infrastructure/persistence/evidence/EvidencePo.java`（PK 改）、`EvidenceRepository.java`（+List 查询）、`EvidenceMapper.java`
- `infrastructure/DefaultAlertSink.java`（TTL 改值 + silence 拦截 + dedup 写 evidence）、`AlertResolveJob.java`（@Scheduled）

**改造（observe-alerting application）**：
- `AlertQueryService.findEvidenceByAlertId` → List

**改造（observe-controlplane）**：
- `AlertController`（+ack/resolve/ignore 3 端点；getEvidence 返回 List）、`AlertDto`（+ack 字段）、`EvidenceDto`（+id/emitSeq）

**改造（observe-bootstrap）**：
- `AlertingPipelineConfig`（装配 silence 全栈 + matcher 注入 sink）
- `application.yml`（+observe.alerting.* 配置）

**改造（docs）**：
- `docs/observe-platform-design.md` TTL 值 C60/W30/I15→C30/W10/I5、2 态→4 态、evidence 1:1→1:N（同步 ADR-0005）
