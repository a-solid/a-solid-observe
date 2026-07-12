# API 事件入口（ApiSource HTTP 触发）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `POST /api/v1/events` HTTP 端点，把请求体转成 `Event` 后调用 `ApiSource.submit(event)` 投递进既有异步流水线，纯异步返回 `202 Accepted`。

**Architecture:** 在 `observe-controlplane` 的 `interfaces` 包下新增 `EventController`（`@RestController`），构造注入 `ApiSource`。controller 接收请求 DTO，服务端固定填充 `sourceType=API` 与 `sourceTs`、生成 `eventId` 放进 `meta.attributes`，调用 `submit()` 后返回 `202` + `{"eventId": "..."}`。不改 `ApiSource`、不改 kernel 的 `Event`/`EventMeta`、不改 `GlobalExceptionHandler`。

**Tech Stack:** Java 17+、Spring Boot Web（`@RestController`）、JUnit 5 + Mockito（`spring-boot-starter-test`，需在 controlplane 模块新增 test 依赖）。

---

## 关于本计划与 spec 的一处偏差（重要）

spec §4 写"`op` 默认 `CREATE`"。但 `com.imsw.observe.kernel.event.model.Op` 枚举**没有 `CREATE`**，实际值为：

```java
public enum Op { INSERT, UPDATE, DELETE, TICK, API, DELAYED }
```

因此本计划把默认值改为 **`INSERT`**（CDC 风格里"创建"的对应值）。这是对 spec 的合理修正，实现时以 `INSERT` 为准。

---

## File Structure

- **Create:** `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/EventController.java`
  - `@RestController`，`/api/v1/events`。构造注入 `ApiSource`。含请求 record `SubmitEventRequest`、响应 record `SubmitEventResponse`、handler 方法。
- **Create:** `observe-controlplane/src/test/java/com/imsw/observe/controlplane/interfaces/EventControllerTest.java`
  - 单元测试，Mockito mock `ApiSource`，验证字段映射、`eventId`、默认 op、缺 source 报错。
- **Modify:** `observe-controlplane/pom.xml`
  - 新增 `spring-boot-starter-test`（test scope，版本由根 pom 的 `spring-boot-dependencies` BOM 管理）。

不改动：`ApiSource.java`、`Event.java`、`EventMeta`、`WorkerConfig`、`GlobalExceptionHandler`。

---

## Task 1: 给 controlplane 模块加测试依赖

**Files:**
- Modify: `observe-controlplane/pom.xml`

`observe-controlplane/pom.xml` 当前 `<dependencies>` 段只有 `observe-kernel`、`observe-config`、`observe-alerting`、`spring-boot-starter-web`，没有测试依赖。需要加 `spring-boot-starter-test`（版本由根 pom 的 `spring-boot-dependencies` BOM 管理，无需写 version）。

- [ ] **Step 1: 加测试依赖**

在 `observe-controlplane/pom.xml` 的 `<dependencies>` 段末尾（`spring-boot-starter-web` 那个 `</dependency>` 之后、`</dependencies>` 之前）插入：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 验证依赖能解析、模块能编译**

Run: `mvn -q -pl observe-controlplane -am dependency:resolve -DincludeScope=test`
Expected: BUILD SUCCESS（说明 `spring-boot-starter-test` 可解析）。

- [ ] **Step 3: Commit**

```bash
git add observe-controlplane/pom.xml
git commit -m "build(controlplane): add spring-boot-starter-test for controller tests"
```

---

## Task 2: 写 EventController 的失败测试

本任务遵循 TDD：先写测试，跑红。

**Files:**
- Create: `observe-controlplane/src/test/java/com/imsw/observe/controlplane/interfaces/EventControllerTest.java`

测试用 Mockito 直接 mock `ApiSource`，不启动 Spring 上下文（纯单元测试，最快、最稳）。`EventController` 还不存在，所以这一步必然编译失败 = 测试红。

关键断言点（对应 spec §4/§5/§7）：
1. 合法请求 → `submit` 被调用一次；
2. 提交的 `Event`：`meta.sourceType == SourceType.API`、`meta.source == req.source`、`sourceTs != null`、`meta.attributes` 含非空 `eventId`；
3. 响应是 `SubmitEventResponse`，其 `eventId` 与提交进 `Event` 的 `eventId` 一致；
4. 不传 `op` → `Event.op == Op.INSERT`（默认值）；
5. `source` 为 null → 抛 `IllegalArgumentException`（被 `GlobalExceptionHandler` 映射成 400，但单测只验证抛异常）。

- [ ] **Step 1: 写测试文件**

创建 `observe-controlplane/src/test/java/com/imsw/observe/controlplane/interfaces/EventControllerTest.java`：

```java
package com.imsw.observe.controlplane.interfaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.infrastructure.source.ApiSource;

class EventControllerTest {

    private ApiSource apiSource;

    private EventController controller;

    @BeforeEach
    void setUp() {
        apiSource = mock(ApiSource.class);
        controller = new EventController(apiSource);
    }

    @Test
    void submitMapsFieldsAndReturnsAccepted() {
        Map<String, Object> after = Map.of("id", 123, "status", "PAID");
        // record 字段顺序: (source, table, op, before, after, attributes)
        EventController.SubmitEventRequest req = new EventController.SubmitEventRequest(
                "order-service", "orders", "UPDATE", null, after, Map.of());

        EventController.SubmitEventResponse resp = controller.submit(req);

        // submit 被调用一次
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(apiSource, times(1)).submit(captor.capture());
        Event submitted = captor.getValue();

        // 字段映射
        assertEquals(SourceType.API, submitted.meta().sourceType());
        assertEquals("order-service", submitted.meta().source());
        assertEquals("orders", submitted.meta().table());
        assertEquals(Op.UPDATE, submitted.op());
        assertEquals(after, submitted.after());
        assertNotNull(submitted.sourceTs());
        // eventId 放进 attributes
        String eventId = (String) submitted.meta().attributes().get("eventId");
        assertNotNull(eventId);
        assertTrue(!eventId.isEmpty());
        // 响应里的 eventId 与提交进 Event 的一致
        assertEquals(eventId, resp.eventId());
    }

    @Test
    void submitDefaultsOpToInsertWhenNull() {
        EventController.SubmitEventRequest req = new EventController.SubmitEventRequest(
                "svc", null, null, null, null, null);

        controller.submit(req);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(apiSource).submit(captor.capture());
        assertEquals(Op.INSERT, captor.getValue().op());
    }

    @Test
    void submitRejectsMissingSource() {
        EventController.SubmitEventRequest req = new EventController.SubmitEventRequest(
                null, null, null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> controller.submit(req));
        verify(apiSource, times(0)).submit(any(Event.class));
    }
}
```

- [ ] **Step 2: 跑测试，确认红（编译失败：EventController 不存在）**

Run: `mvn -q -pl observe-controlplane -am test -Dtest=EventControllerTest`
Expected: 编译错误 —— `EventController` / `SubmitEventRequest` / `SubmitEventResponse` 无法解析。这是预期的"红"。

---

## Task 3: 实现 EventController 让测试转绿

**Files:**
- Create: `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/EventController.java`

实现要点（对应 spec §3/§4/§5/§6）：
- `@RestController` + `@RequestMapping("/api/v1/events")`，构造注入 `ApiSource`。
- handler `submit(req)` 返回裸 `SubmitEventResponse`（body 对象）。方法上加 `@ResponseStatus(HttpStatus.ACCEPTED)` 让 HTTP 状态码为 202（该注解只影响 HTTP 响应状态，不影响测试里直接调用方法拿到的返回值——测试断言的是返回对象的 `eventId()`）。这样 202 语义与"测试直接断言 body"两者都满足。
- `source` 为 null/blank → 抛 `IllegalArgumentException`（由 `GlobalExceptionHandler` 映射成 400）。
- 默认 op：`req.op()` 为 null → `Op.INSERT`；否则 `Op.valueOf(req.op())`（非法值会抛，被 GlobalExceptionHandler 当 500；spec 没要求校验枚举，保持简单）。
- 生成 `eventId = UUID.randomUUID().toString()`，写进 `attributes`；若调用方传了 `attributes`，复制后覆盖 `eventId`（服务端是唯一事实来源）。
- 固定 `sourceType = SourceType.API`、`sourceTs = Instant.now()`、`db = null`。

- [ ] **Step 1: 写 EventController**

创建 `observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/EventController.java`：

```java
package com.imsw.observe.controlplane.interfaces;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.infrastructure.source.ApiSource;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final ApiSource apiSource;

    public EventController(final ApiSource apiSource) {
        this.apiSource = apiSource;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SubmitEventResponse submit(@RequestBody final SubmitEventRequest req) {
        if (req.source() == null || req.source().isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        Op op = req.op() == null ? Op.INSERT : Op.valueOf(req.op());
        String eventId = UUID.randomUUID().toString();

        Map<String, Object> attributes = new HashMap<>();
        if (req.attributes() != null) {
            attributes.putAll(req.attributes());
        }
        attributes.put("eventId", eventId);

        Event event = new Event(
                new Event.EventMeta(
                        SourceType.API,
                        req.source(),
                        null,
                        req.table(),
                        attributes),
                req.before(),
                req.after(),
                op,
                Instant.now());

        apiSource.submit(event);
        return new SubmitEventResponse(eventId);
    }

    public record SubmitEventRequest(
            String source,
            String table,
            String op,
            Map<String, Object> before,
            Map<String, Object> after,
            Map<String, Object> attributes) {}

    public record SubmitEventResponse(String eventId) {}
}
```

- [ ] **Step 2: 跑测试，确认全绿**

Run: `mvn -q -pl observe-controlplane -am test -Dtest=EventControllerTest`
Expected: 3 个测试全 PASS。

- [ ] **Step 3: Commit**

```bash
git add observe-controlplane/src/main/java/com/imsw/observe/controlplane/interfaces/EventController.java \
        observe-controlplane/src/test/java/com/imsw/observe/controlplane/interfaces/EventControllerTest.java
git commit -m "feat(controlplane): add POST /api/v1/events entry point for ApiSource"
```

---

## Task 4: 整体验证（编译 + 全模块测试）

**Files:** 无改动

- [ ] **Step 1: 全量编译 + 测试**

Run: `mvn -q -pl observe-controlplane -am test`
Expected: BUILD SUCCESS，`EventControllerTest` 3 个用例通过，且不破坏既有测试。

- [ ] **Step 2: （可选）启动验证 HTTP 行为**

若本地环境可启动：
Run: `mvn -q -pl observe-bootstrap -am package -DskipTests && java -jar observe-bootstrap/target/observe-bootstrap-*.jar`
然后发请求：
```bash
curl -i -X POST http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"source":"order-service","table":"orders","op":"UPDATE","after":{"id":123,"status":"PAID"}}'
```
Expected: `HTTP/1.1 202`，body 形如 `{"eventId":"<uuid>"}`。

- [ ] **Step 3: 最终 Commit（如有 lint/格式化改动）**

Run: `mvn -q -pl observe-controlplane spotless:apply` （若配置了 spotless）
然后：
```bash
git add -A
git commit -m "style(controlplane): apply spotless formatting" --allow-empty
```
（无改动则跳过。）

---

## 完成标准

- `POST /api/v1/events` 端点存在并返回 202 + `eventId`。
- `ApiSource.submit` 被正确调用，`Event` 字段映射符合 spec（`sourceType=API`、`sourceTs` 服务端生成、`eventId` 在 attributes）。
- 缺 `source` → 400。
- 不改 `ApiSource`、kernel 模型、`GlobalExceptionHandler`。
- controlplane 测试依赖已加，`EventControllerTest` 通过。
