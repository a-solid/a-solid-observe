# 延时动作模型重设计（DELAYED）

**Status**: draft / **待重新设计** —— 经 brainstorming 多轮迭代，抽象层次尚未定稿，用户明确要求"后面再继续研究是否 OK"。本文件归档当前讨论的候选方案与开放问题，**不作为实施依据**，等抽象定稿后再转正式 spec。
**Date**: 2026-07-19
**Author**: brainstorming session
**相关**: 扇出改造（已拆为独立可执行 spec）见 `2026-07-19-subscription-multi-pipeline-fanout.md`

## 背景：当前延时动作的两个入口

延时动作当前存在**两个入口**，且都不可用/不可维护：

- **入口 A（脚本运行时 `delayed.schedule(...)`）**：`delayed` binding **从未注入脚本上下文**，是死代码/预留端口，脚本根本调不到。`DelayedEventStore` 端口 + `InMemoryDelayedEventStore` 实现存在，但无脚本调用方。
- **入口 B（subscription 配置 `actionType=SCHEDULE/CANCEL`）**：配置 + `DelayedActionHandler` 接线完整，但**当前没有任何 subscription 实际配置过 SCHEDULE/CANCEL**（项目处于开发设计阶段）。

项目处于开发设计阶段、无线上数据、无向后兼容包袱，适合一次性把延时模型设计干净。

## 目标

废弃两个旧入口，统一为一种**可维护、schedule/cancel 配对、声明式**的延时模型。

## 已讨论的候选方案（按讨论顺序）

### 方案 1：保留入口 A，废弃入口 B（脚本内延时）

- 给脚本注入 `delayed` binding，封装 `DelayedEventStore.schedule/cancel`。
- 优点：扇出语义天然自洽（脚本在哪个 pipeline 跑就 schedule 给哪个 pipeline）；能力上是入口 B 的超集。
- **被否决原因**：用户认为"延时逻辑藏在脚本里不好维护"，希望延时作为**配置项**而非代码。

### 方案 2：延时配置挂在 pipeline 上

- pipeline 定义里声明延时行为（correlationKeyPath / delay），脚本约定 `cancel(event)` 回调或 pipeline 配 correlationKey。
- 优点：延时从脚本黑箱变成 pipeline 定义里的一等公民。
- **暴露的问题**：cancel 的触发源通常与 schedule 不同（INSERT vs UPDATE，甚至不同 pipeline），cancel 路由天然在 subscription 层。挂在 pipeline 上无法表达"某事件来了取消挂起任务"。用户亦指出"cancel 和 delay 分离不好维护"。

### 方案 3：delayedRule 作为独立配置实体

```
delayedRule "订单超时复查" {
  correlationKeyPath: "after.orderId",     // schedule/cancel 共享 key（配对纽带）
  schedule: { when: {...}, delay: "PT30M" }
  cancel:  { when: {...} }                 // 可选，when 可指向不同事件
  pipelineIds: [pipelineA]
}
```
- 优点：schedule/cancel 在一个 rule 里配对；when 各自声明解决来源不同。
- **暴露的问题**：`delayedRule` 结构与 subscription 几乎同构（都是"事件条件 + pipelineIds"），引入新实体可能冗余。

### 方案 4：subscription 加 delayed 属性（当前 draft spec 的形态）

普通 subscription 扩展一个可选 `delayed` 属性，含 schedule/cancel 两面。后进一步演化为——

### 方案 5：引入新 subscription 类型 `DELAYED`（**当前 draft 形态**）

`subscriptions` 表加 `type` 列（`FANOUT` / `DELAYED`）：

- **FANOUT**：普通扇出（见扇出 spec）。
- **DELAYED**：专门处理"延时 + 可取消"，配置含 `schedule`（必填）+ `cancel`（可选），各自声明 `when` 触发条件，共享 `correlationKeyPath`。

决策摘要：
- D6: 延时统一走 DELAYED 订阅类型；废弃入口 A 和入口 B。
- D7: schedule 必填、cancel 可选。
- D8: schedule/cancel 各自声明 when，可指向不同事件。
- D9: correlationKeyPath 在 schedule/cancel 各自声明，若都配则引擎校验一致（不一致 warn）。

## 待重新研究的开放问题

以下问题导致本 spec 无法定稿，需后续 brainstorming 解决：

1. **抽象层次**：DELAYED 作为独立 subscription 类型（方案 5）是否最佳？vs 方案 2（pipeline 上声明）/ 方案 3（独立 delayedRule 实体）/ 方案 4（subscription 加 delayed 属性）。核心张力：schedule/cancel 要在一起（→ 倾向独立实体或 DELAYED 类型），但 cancel 触发源不同（→ 倾向 subscription 层路由）。

2. **schema 复杂度**：若采用 DELAYED 类型，FANOUT 用原有 source 扁平列当 when，DELAYED 用新增 `schedule_when`/`cancel_when` JSON 列，两组条件表达不对称。可能需要统一为"when 全部 JSON 化"或重新设计列结构。

3. **cancel 与扇出的关系**：cancelOn 命中事件时，是只取消挂起任务（不扇出 pipeline），还是既取消又正常扇出？方案 5 定为 schedule/cancel 互斥（一个事件只命中一个 when），但语义细节待确认。

4. **`correlationKeyPath` 不一致的处理**：当前定为 warn，可能需要改为配置期强校验拒绝。

5. **`Matched` sealed interface 形态**：若 DELAYED 类型落地，matcher 需返回 `MatchedFanout`/`MatchedDelay` sealed 类型，具体形态待定。

## 不在本 spec 范围

- 扇出（subscription 多 pipeline）—— 见 `2026-07-19-subscription-multi-pipeline-fanout.md`，已独立推进。
- 扇出 spec 明确**不动** `actionType`/`Action`/`DelayedActionHandler`，留待本 spec 定稿后处理。

## 保留的延时基础设施（无论最终方案如何，这些大概率保留）

- `DelayedEventStore` 端口 + `InMemoryDelayedEventStore` 实现（schedule/cancel 原语）。
- `DelayedEvent` 事件模型 + `EventPaths` key 提取。
- 到点重放绕过 matcher 直调 runner 的语义（ADR-0006 §9.2）。
