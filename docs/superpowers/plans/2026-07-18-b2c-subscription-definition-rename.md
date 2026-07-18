# B2c 命名重构（Subscription → SubscriptionDefinition）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superagent-driven-development (recommended). Steps use checkbox (`- [ ]`).

**Goal:** 把 observe-config 模块的配置态 `Subscription` 类重命名为 `SubscriptionDefinition`，对齐 `PipelineDefinition` 命名约定（ADR-0001 / CONTEXT.md「配置态 XxxDefinition / 运行态裸名」）。

**Architecture:** 纯机械重命名，scope = observe-config 的 `config.domain.Subscription`（配置态）。**不**动 observe-pipeline 的运行态 `pipeline.domain.subscription.Subscription`（它保持裸名）。受影响：config 模块内所有引用 config.Subscription 的文件 + controlplane 的 SubscriptionController/DTO + bootstrap 的 bean 装配 + 测试。

**Tech Stack:** Java 17、Spring Boot、IDE-safe 全局重命名。

> **本子计划是 B2 的第 3 份（共 4 份）**。前置 B2a/B2b（已合并）。后续 B2d（延时端口）。本份纯重命名，不改字段/行为/Event/namespace。

## Global Constraints

- 设计权威：`CONTEXT.md` 模型分层节 + `docs/adr/0001-config-vs-runtime-model-split.md`。
- **只重命名 config.domain.Subscription → SubscriptionDefinition**。运行态 `pipeline.domain.subscription.Subscription` 不动（保持裸名）。
- 不改任何字段、方法签名、行为、SQL、namespace 逻辑。纯 rename refactor。
- 每批结束（B2c 完成）`mvn compile` + `mvn test` 全绿。
- `Action.ActionType` / `Subscription.Status` 等嵌套枚举：随外层类重命名为 `SubscriptionDefinition.ActionType` / `SubscriptionDefinition.Status`。

## File Structure

**Rename class:**
- `observe-config/domain/Subscription.java` → `SubscriptionDefinition.java`（类名 + 嵌套枚举 ActionType/Status 跟随）

**Update references (config module):**
- `SubscriptionPo.java`（无直接引用 domain.Subscription——PO 是独立类，确认无需改）
- `SubscriptionMapper.java`（`import ...config.domain.Subscription` → `SubscriptionDefinition`；方法签名 `Subscription` → `SubscriptionDefinition`）
- `SubscriptionRepository.java`（绑 SubscriptionPo，不引 domain——确认）
- `SubscriptionCrudService.java`（方法签名 Subscription → SubscriptionDefinition）
- `PipelineRegistryLoader.java`（import config.Subscription 用于 toPipelineSubscription 转换 → SubscriptionDefinition；运行态 Subscription 保持）
- `SubscriptionController.java` + `SubscriptionDto.java` + `SubscriptionFields`（controlplane，引用 config.Subscription）

**Update references (bootstrap):**
- `WorkerConfig.java`（若有 Subscription 相关 bean 装配引用 config.Subscription）

**Update tests:**
- `SubscriptionCrudServiceTest.java`、`EndToEndFlowTest.java`（引用 config.Subscription 构造 fixture）

## Task 分解（单 task，因纯机械重命名）

### Task 1: 重命名 config.domain.Subscription → SubscriptionDefinition + 全引用更新 + 全量绿

**Files:**
- Rename: `observe-config/domain/Subscription.java` → `SubscriptionDefinition.java`
- Modify: `SubscriptionMapper.java`, `SubscriptionCrudService.java`, `PipelineRegistryLoader.java`, `SubscriptionController.java`, `SubscriptionDto.java`, `WorkerConfig.java`（如需）, `SubscriptionCrudServiceTest.java`, `EndToEndFlowTest.java`

**Interfaces:**
- Consumes: B2a/B2b 现状。
- Produces: config.domain.SubscriptionDefinition（配置态），运行态 pipeline Subscription 不变。

- [ ] **Step 1: 重命名类文件 + 类名**

`git mv observe-config/src/main/java/com/imsw/observe/config/domain/Subscription.java observe-config/src/main/java/com/imsw/observe/config/domain/SubscriptionDefinition.java`
编辑文件：`public record Subscription(` → `public record SubscriptionDefinition(`；嵌套 `ActionType`/`Status` 枚举的引用点随外层类名自动（它们是 `Subscription.ActionType`，改成 `SubscriptionDefinition.ActionType`）。

- [ ] **Step 2: 全局更新引用（codegraph 找全）**

用 `grep -rn "config.domain.Subscription\b" observe-*/src` 找所有引用点。逐个：
- `import com.imsw.observe.config.domain.Subscription;` → `import com.imsw.observe.config.domain.SubscriptionDefinition;`
- 类型引用 `Subscription`（在引用 config domain 的上下文）→ `SubscriptionDefinition`
- `Subscription.ActionType` / `Subscription.Status` → `SubscriptionDefinition.ActionType` / `SubscriptionDefinition.Status`

**注意区分**：`pipeline.domain.subscription.Subscription`（运行态）的引用**不改**。只改 `config.domain.Subscription` 的引用。grep 时按 import 路径区分。

受影响文件（预期）：`SubscriptionMapper`、`SubscriptionCrudService`、`PipelineRegistryLoader`、`SubscriptionController`、`SubscriptionDto`、`SubscriptionCrudServiceTest`、`EndToEndFlowTest`、可能 `WorkerConfig`。

- [ ] **Step 3: 编译 + 全量测试**

Run: `mvn clean test`
Expected: BUILD SUCCESS，全绿（测试数与 B2b 收尾一致 59，无新增无减少——纯 rename 不改行为）。

- [ ] **Step 4: 提交**

```bash
git add -A observe-config/ observe-controlplane/ observe-bootstrap/
git commit -m "$(cat <<'EOF'
refactor(config): rename Subscription → SubscriptionDefinition (ADR-0001)

配置态 Subscription 重命名为 SubscriptionDefinition，对齐 PipelineDefinition 命名约定（配置态 XxxDefinition / 运行态裸名）。运行态 pipeline.domain.subscription.Subscription 不变。纯 rename，无字段/行为变更。

Co-Authored-By: Claude <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

1. **Spec 覆盖**：B2 spec §5.2 B2.3 命名重构部分 = Task 1。延时端口（B2.3 另一半）留 B2d。
2. **占位扫描**：无 TBD；步骤明确（git mv + grep 全引用 + 编译测试）。
3. **范围**：仅 config.domain.Subscription 重命名，运行态 Subscription 不动。
4. **风险**：纯 rename，编译器会抓所有遗漏引用。grep 按 import 路径区分 config vs pipeline Subscription。
