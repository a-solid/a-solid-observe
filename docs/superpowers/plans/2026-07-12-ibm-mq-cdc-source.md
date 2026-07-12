# IBM MQ CDC 来源接入（含合并 CdcMqSource + 可插拔 MessageParser）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 接入 IBM MQ 作为 CDC 来源（worker 作为 JMS consumer 主动消费队列，XML 消息 → `Event`），落地可插拔 `MessageParser` 抽象，并合并掉 `CdcMqSource` 空壳胶水层（具体来源直接 `implements Source`）。

**Architecture:**
- 新增 `MessageParser<M>` 接口（pipeline 层）+ `MessageParseException`（kernel 层）。
- 新增 `IbmMqCdcSource implements Source`（bootstrap 层）：内部 JMS consumer（`CLIENT_ACKNOWLEDGE`）+ 攒批，at-least-once（ack 边界 = `listener.onBatch` 成功）。
- 新增 `IbmMqXmlParser implements MessageParser<TextMessage>`（bootstrap 层，示例映射，真实规则用户自实现）。
- 新增 `IbmMqProperties`（`@ConfigurationProperties("observe.worker.ibm-mq")`）+ `WorkerConfig` 条件装配。
- 合并：`InMemoryCdcMessageSource` 改为 `implements Source`（保留 `push()` 供测试/demo），删除 `CdcMessageSource` 接口与 `CdcMqSource` 类，删除 `WorkerConfig` 中 `cdcMqSource` bean。

**Tech Stack:** Java 17+、Spring Boot、JMS（`javax.jms`，由 `com.ibm.mq.allclient` 提供）、JUnit 5 + Mockito、JDK `javax.xml.parsers`。

---

## 关键事实（已核实，影响实现）

- `Op` 枚举值：`INSERT, UPDATE, DELETE, TICK, API, DELAYED`（无 `CREATE`）。
- `Source` 接口：`SourceType type()` / `void start(EventListener listener)` / `void stop()`。
- `EventListener.onBatch(List<Event>)`。
- `Event.EventMeta(SourceType sourceType, String source, String db, String table, Map<String,Object> attributes)`。
- `com.ibm.mq.allclient` **不在 Spring Boot BOM 中** → 必须在 bootstrap pom 显式写版本（用 property）。
- `InMemoryCdcMessageSource` 当前被 `EndToEndFlowTest`（`@Autowired` + `cdcSource.push(List.of(event))`）和 `WorkerConfig`（作为 `cdcMqSource` 的入参）引用。合并后必须让 `InMemoryCdcSource` 保留 `push()` 并在 `WorkerConfig` 里被 `start(dispatcher::onBatch)`，测试才能继续工作。
- `DemoMain` / `DemoPipelineFactory` **不使用** `InMemoryCdcMessageSource`（独立无 Spring demo），无需改造。
- JMS API 包名：IBM MQ 客户端提供 `javax.jms.*`（JMS 2.0 兼容）。本计划用 `javax.jms`。

---

## File Structure

**新增（pipeline 层）：**
- `observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/MessageParser.java` — 接口 `MessageParser<M> { Event parse(M raw) throws MessageParseException; }`

**新增（kernel 层）：**
- `observe-kernel/src/main/java/com/imsw/observe/kernel/error/MessageParseException.java` — extends `ObserveException`

**新增（bootstrap 层）：**
- `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/IbmMqCdcSource.java` — `implements Source`，JMS consumer + 攒批 + at-least-once
- `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/IbmMqXmlParser.java` — `implements MessageParser<TextMessage>`，示例 XML 映射
- `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/config/IbmMqProperties.java` — `@ConfigurationProperties("observe.worker.ibm-mq")`
- `observe-bootstrap/src/test/java/com/imsw/observe/bootstrap/worker/source/IbmMqXmlParserTest.java` — XML 解析单测

**改造（bootstrap 层）：**
- `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/InMemoryCdcMessageSource.java` — `implements Source`（保留 `push()`），rename 类为 `InMemoryCdcSource`
- `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/config/WorkerConfig.java` — 删 `cdcMqSource`、改 `cdcMessageSource` → `inMemoryCdcSource` 并 `start`、新增 `ibmMqCdcSource` 条件 bean
- `observe-bootstrap/src/test/java/com/imsw/observe/bootstrap/EndToEndFlowTest.java` — import/类型从 `InMemoryCdcMessageSource` → `InMemoryCdcSource`
- `observe-bootstrap/pom.xml` — 加 `com.ibm.mq.allclient` 依赖（带版本 property）
- `observe-bootstrap/src/main/resources/application.yml` — 加 `observe.worker.ibm-mq` 段

**删除（pipeline 层）：**
- `observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/CdcMessageSource.java`
- `observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/CdcMqSource.java`

---

## 执行顺序提示

任务编号大致按依赖顺序，但**两处需提前**（执行时按此顺序，不要严格按编号）：
1. **Task 6（加 IBM MQ 依赖）必须在 Task 3/4（IbmMqXmlParser 测试/实现）之前执行** —— `IbmMqXmlParser` 与其测试用到 `javax.jms.TextMessage`，依赖 IBM MQ 客户端。
2. 其余任务按编号顺序即可。

推荐执行顺序：Task 1 → 2 → **6** → 3 → 4 → 5 → 7 → 8 → 9 → 10 → 11 → 12。

---

## Task 1: 新增 MessageParseException（kernel 层）

**Files:**
- Create: `observe-kernel/src/main/java/com/imsw/observe/kernel/error/MessageParseException.java`

- [ ] **Step 1: 写类**

```java
package com.imsw.observe.kernel.error;

public class MessageParseException extends ObserveException {

    public MessageParseException(final String message) {
        super(message);
    }

    public MessageParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: 编译 kernel**

Run: `mvn -q -pl observe-kernel compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add observe-kernel/src/main/java/com/imsw/observe/kernel/error/MessageParseException.java
git commit -m "feat(kernel): add MessageParseException for source message parsing errors"
```

---

## Task 2: 新增 MessageParser 接口（pipeline 层）

**Files:**
- Create: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/MessageParser.java`

- [ ] **Step 1: 写接口**

```java
package com.imsw.observe.pipeline.infrastructure.source;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.error.MessageParseException;

public interface MessageParser<M> {

    Event parse(M raw) throws MessageParseException;
}
```

- [ ] **Step 2: 编译 pipeline**

Run: `mvn -q -pl observe-pipeline -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/MessageParser.java
git commit -m "feat(pipeline): add pluggable MessageParser<M> interface"
```

---

## Task 3: 写 IbmMqXmlParser 的失败测试（TDD）

这是核心逻辑，重点测。映射规则见 spec §6（示例，真实规则用户后续自行调整）。

**Files:**
- Create: `observe-bootstrap/src/test/java/com/imsw/observe/bootstrap/worker/source/IbmMqXmlParserTest.java`

测试用 Mockito mock `TextMessage`，`when(msg.getText()).thenReturn(...)`。断言解析出的 `Event` 字段。

- [ ] **Step 1: 写测试**

```java
package com.imsw.observe.bootstrap.worker.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.jms.TextMessage;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.error.MessageParseException;

class IbmMqXmlParserTest {

    private final IbmMqXmlParser parser = new IbmMqXmlParser();

    private static TextMessage textMessage(final String body) throws Exception {
        TextMessage msg = mock(TextMessage.class);
        when(msg.getText()).thenReturn(body);
        return msg;
    }

    @Test
    void parsesFullEvent() throws Exception {
        TextMessage msg = textMessage("""
                <event>
                  <source>order-service</source>
                  <table>orders</table>
                  <op>CREATE</op>
                  <after>
                    <id>123</id>
                    <status>PAID</status>
                  </after>
                </event>
                """);

        var event = parser.parse(msg);

        assertEquals(SourceType.CDC, event.meta().sourceType());
        assertEquals("order-service", event.meta().source());
        assertEquals("orders", event.meta().table());
        // 示例映射: "CREATE" 归一化为 Op.INSERT (Op 无 CREATE)
        assertEquals(Op.INSERT, event.op());
        assertEquals("123", event.after().get("id"));
        assertEquals("PAID", event.after().get("status"));
        assertNotNull(event.sourceTs());
    }

    @Test
    void parsesBeforeAndAfter() throws Exception {
        TextMessage msg = textMessage("""
                <event>
                  <source>svc</source>
                  <op>UPDATE</op>
                  <before><id>1</id></before>
                  <after><id>1</id><status>OK</status></after>
                </event>
                """);

        var event = parser.parse(msg);

        assertEquals(Op.UPDATE, event.op());
        assertEquals("1", event.before().get("id"));
        assertEquals("OK", event.after().get("status"));
    }

    @Test
    void rejectsMalformedXml() throws Exception {
        TextMessage msg = textMessage("<event><source>svc</source>"); // 未闭合
        assertThrows(MessageParseException.class, () -> parser.parse(msg));
    }

    @Test
    void rejectsMissingSource() throws Exception {
        TextMessage msg = textMessage("<event><op>INSERT</op></event>");
        assertThrows(MessageParseException.class, () -> parser.parse(msg));
    }
}
```

> 注：示例映射把 `<op>CREATE</op>` 归一化为 `Op.INSERT`（因为 `Op` 枚举无 `CREATE`）。真实规则由用户实现时按需调整。

- [ ] **Step 2: 跑测试，确认红（IbmMqXmlParser 不存在）**

Run: `mvn -q -pl observe-bootstrap -am test -Dtest=IbmMqXmlParserTest`
Expected: 编译错误 —— `IbmMqXmlParser` 无法解析。预期红。

---

## Task 4: 实现 IbmMqXmlParser 让测试转绿

**Files:**
- Create: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/IbmMqXmlParser.java`

- [ ] **Step 1: 写实现**

```java
package com.imsw.observe.bootstrap.worker.source;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jms.JMSException;
import javax.jms.TextMessage;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.error.MessageParseException;
import com.imsw.observe.pipeline.infrastructure.source.MessageParser;

/**
 * IBM MQ XML 消息解析器。示例映射（真实规则由用户自行实现/调整）。
 *
 * 约定 XML:
 * <pre>{@code
 * <event>
 *   <source>..</source>      必填
 *   <table>..</table>        可选
 *   <op>CREATE|UPDATE|DELETE|..</op>  可选, 默认 INSERT; CREATE 归一化为 INSERT
 *   <before><k>v</k>..</before>       可选
 *   <after><k>v</k>..</after>         可选
 * </event>
 * }</pre>
 */
public final class IbmMqXmlParser implements MessageParser<TextMessage> {

    private final DocumentBuilder documentBuilder;

    public IbmMqXmlParser() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            this.documentBuilder = f.newDocumentBuilder();
        } catch (Exception e) {
            throw new IllegalStateException("cannot init DocumentBuilder", e);
        }
    }

    @Override
    public Event parse(final TextMessage raw) throws MessageParseException {
        String xml;
        try {
            xml = raw.getText();
        } catch (JMSException e) {
            throw new MessageParseException("cannot read text from JMS message", e);
        }
        Document doc;
        try {
            doc = documentBuilder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        } catch (Exception e) {
            throw new MessageParseException("malformed XML", e);
        }

        Element root = doc.getDocumentElement();
        String source = childText(root, "source");
        if (source == null || source.isBlank()) {
            throw new MessageParseException("missing <source>");
        }
        String table = childText(root, "table");
        Op op = parseOp(childText(root, "op"));
        Map<String, Object> before = childMap(root, "before");
        Map<String, Object> after = childMap(root, "after");

        Event.EventMeta meta = new Event.EventMeta(
                SourceType.CDC, source, null, table, Map.of());
        return new Event(meta, before, after, op, Instant.now());
    }

    private static Op parseOp(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Op.INSERT;
        }
        String upper = raw.trim().toUpperCase();
        if ("CREATE".equals(upper)) {
            return Op.INSERT;
        }
        return Op.valueOf(upper);
    }

    private static String childText(final Element parent, final String name) {
        NodeList list = parent.getElementsByTagName(name);
        if (list.getLength() == 0) {
            return null;
        }
        return list.item(0).getTextContent();
    }

    private static Map<String, Object> childMap(final Element parent, final String name) {
        NodeList list = parent.getElementsByTagName(name);
        if (list.getLength() == 0) {
            return Map.of();
        }
        Element container = (Element) list.item(0);
        Map<String, Object> map = new LinkedHashMap<>();
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                map.put(n.getNodeName(), n.getTextContent());
            }
        }
        return Map.copyOf(new HashMap<>(map));
    }
}
```

- [ ] **Step 2: 跑测试，确认绿**

Run: `mvn -q -pl observe-bootstrap -am test -Dtest=IbmMqXmlParserTest`
Expected: 4 个测试全 PASS。

> 若编译失败提示 `javax.jms` 不可用：因为 IBM MQ 客户端依赖还未加入。本 Task 的测试需要 `javax.jms.TextMessage` —— 先在 Task 6 加 IBM MQ 依赖后才能编译。**因此把 Task 6（加 IBM MQ 依赖）调整到本 Task 之前执行**：实际执行时先做 Task 6 的 Step 1（加 pom 依赖），再回来跑本测试。下方 Task 6 已注明。

- [ ] **Step 3: Commit**

```bash
git add observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/IbmMqXmlParser.java \
        observe-bootstrap/src/test/java/com/imsw/observe/bootstrap/worker/source/IbmMqXmlParserTest.java
git commit -m "feat(bootstrap): add IbmMqXmlParser (example XML->Event mapping)"
```

---

## Task 5: 合并 —— InMemoryCdcMessageSource 改 implements Source

把内存来源从 `implements CdcMessageSource` 改为 `implements Source`，保留 `push()` 供测试/demo 用。类 rename 为 `InMemoryCdcSource`（旧名作废）。

**Files:**
- Modify: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/InMemoryCdcMessageSource.java`（rename → `InMemoryCdcSource.java`）
- Modify: `observe-bootstrap/src/test/java/com/imsw/observe/bootstrap/EndToEndFlowTest.java`（类型/import 更名）

- [ ] **Step 1: 删除旧文件、创建新文件**

删除 `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/InMemoryCdcMessageSource.java`，新建 `InMemoryCdcSource.java`：

```java
package com.imsw.observe.bootstrap.worker.source;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.EventListener;
import com.imsw.observe.pipeline.application.Source;

/**
 * 内存 CDC 来源（demo/测试用）。start 后, 外部调 push() 把事件喂进流水线。
 */
public final class InMemoryCdcSource implements Source {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryCdcSource.class);

    private EventListener listener;

    @Override
    public SourceType type() {
        return SourceType.CDC;
    }

    @Override
    public void start(final EventListener listener) {
        this.listener = listener;
        LOG.info("InMemoryCdcSource started");
    }

    @Override
    public void stop() {
        this.listener = null;
        LOG.info("InMemoryCdcSource stopped");
    }

    public void push(final List<Event> events) {
        if (listener != null && events != null && !events.isEmpty()) {
            listener.onBatch(events);
        }
    }
}
```

- [ ] **Step 2: 更新 EndToEndFlowTest 的引用**

在 `EndToEndFlowTest.java` 中：
- import 行：`com.imsw.observe.bootstrap.worker.source.InMemoryCdcMessageSource` → `com.imsw.observe.bootstrap.worker.source.InMemoryCdcSource`
- 字段声明：`private InMemoryCdcMessageSource cdcSource;` → `private InMemoryCdcSource cdcSource;`

（`cdcSource.push(List.of(event))` 调用保持不变 —— `push()` 仍存在。）

- [ ] **Step 3: 此时先不跑全量测试（WorkerConfig 还引用旧类型，下一步改）**

---

## Task 6: 加 IBM MQ 客户端依赖（bootstrap pom）

`com.ibm.mq.allclient` 不在 Spring Boot BOM，需显式版本。

**Files:**
- Modify: `observe-bootstrap/pom.xml`
- Modify: `pom.xml`（根 pom 加版本 property —— 放在已有 properties 段）

- [ ] **Step 1: 根 pom 加版本 property**

在根 `pom.xml` 的 `<properties>` 段（与 `spring-boot.version` 同处）加：

```xml
        <ibm-mq-client.version>9.4.0.0</ibm-mq-client.version>
```

> 版本可按实际可用版本调整；9.4.x 系列兼容 JMS 2.0。

- [ ] **Step 2: bootstrap pom 加依赖**

在 `observe-bootstrap/pom.xml` 的 `<dependencies>` 段（`h2` 之后、`</dependencies>` 之前）加：

```xml
        <dependency>
            <groupId>com.ibm.mq</groupId>
            <artifactId>com.ibm.mq.allclient</artifactId>
            <version>${ibm-mq-client.version}</version>
        </dependency>
```

- [ ] **Step 3: 验证依赖可解析**

Run: `mvn -q -pl observe-bootstrap -am dependency:resolve`
Expected: BUILD SUCCESS（`com.ibm.mq.allclient` 下载成功，`javax.jms.*` 可用）。

- [ ] **Step 4: Commit**

```bash
git add pom.xml observe-bootstrap/pom.xml
git commit -m "build(bootstrap): add IBM MQ allclient dependency for JMS CDC source"
```

---

## Task 7: 实现 IbmMqCdcSource（JMS consumer + 攒批 + at-least-once）

**Files:**
- Create: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/IbmMqCdcSource.java`

设计要点（spec §5/§5.1）：
- 构造注入：`ConnectionFactory`、queue 名、`MessageParser<TextMessage>`、`batchSize`、`batchTimeoutMillis`。
- `start(listener)`：保存 listener；创建 JMS `Connection` + `Session`（`CLIENT_ACKNOWLEDGE`）+ `MessageConsumer`；注册 `MessageListener`；`connection.start()`。
- `onMessage(msg)`：`parser.parse` → 入 buffer（连同原 `Message`，供 ack 用）；buffer 满 `batchSize` 立即 flush；超时由一个调度线程触发 flush（v1 简单做法：`onMessage` 里检查上次 flush 时间）。
- `flush()`：`listener.onBatch(batch)` 成功 → 对该批每条 `msg.acknowledge()`；抛异常 → 不 ack（MQ 重投）。
- 解析失败（`MessageParseException`）：**不 ack**，记日志（含原始），让 MQ 重投。
- `stop()`：关 connection（不 ack 未处理的消息）。

- [ ] **Step 1: 写实现**

```java
package com.imsw.observe.bootstrap.worker.source;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.error.MessageParseException;
import com.imsw.observe.pipeline.application.EventListener;
import com.imsw.observe.pipeline.application.Source;
import com.imsw.observe.pipeline.infrastructure.source.MessageParser;

/**
 * IBM MQ CDC 来源。worker 作为 JMS consumer 消费队列, CLIENT_ACKNOWLEDGE 实现 at-least-once。
 * ack 边界 = listener.onBatch 成功 (进入 SourceDispatcher)。pipeline 异步执行失败不回 MQ。
 */
public final class IbmMqCdcSource implements Source, MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(IbmMqCdcSource.class);

    private final ConnectionFactory connectionFactory;
    private final String queueName;
    private final MessageParser<TextMessage> parser;
    private final int batchSize;
    private final long batchTimeoutMillis;

    private final ReentrantLock lock = new ReentrantLock();

    private EventListener listener;

    private Connection connection;
    private Session session;
    private MessageConsumer consumer;

    private final List<Event> eventBuffer = new ArrayList<>();
    private final List<Message> messageBuffer = new ArrayList<>();
    private long lastFlushMs = 0L;

    public IbmMqCdcSource(
            final ConnectionFactory connectionFactory,
            final String queueName,
            final MessageParser<TextMessage> parser,
            final int batchSize,
            final long batchTimeoutMillis) {
        this.connectionFactory = connectionFactory;
        this.queueName = queueName;
        this.parser = parser;
        this.batchSize = batchSize;
        this.batchTimeoutMillis = batchTimeoutMillis;
    }

    @Override
    public SourceType type() {
        return SourceType.CDC;
    }

    @Override
    public void start(final EventListener listener) {
        this.listener = listener;
        try {
            connection = connectionFactory.createConnection();
            session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            Destination dest = session.createQueue(queueName);
            consumer = session.createConsumer(dest);
            consumer.setMessageListener(this);
            connection.start();
            lastFlushMs = System.currentTimeMillis();
            LOG.info("IbmMqCdcSource started, queue={}", queueName);
        } catch (JMSException e) {
            throw new IllegalStateException("cannot start IBM MQ CDC source", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (consumer != null) {
                consumer.close();
            }
            if (session != null) {
                session.close();
            }
            if (connection != null) {
                connection.close();
            }
        } catch (JMSException e) {
            LOG.warn("error closing IBM MQ CDC source", e);
        }
        LOG.info("IbmMqCdcSource stopped");
    }

    @Override
    public void onMessage(final Message message) {
        lock.lock();
        try {
            Event event;
            try {
                event = parser.parse((TextMessage) message);
            } catch (MessageParseException e) {
                // 不 ack -> MQ 重投. 记日志(含原始文本)便于排查.
                LOG.error("parse failed, will redeliver; raw={}", rawOf(message), e);
                return;
            }
            eventBuffer.add(event);
            messageBuffer.add(message);
            long now = System.currentTimeMillis();
            boolean sizeReached = eventBuffer.size() >= batchSize;
            boolean timeoutReached = (now - lastFlushMs) >= batchTimeoutMillis;
            if (sizeReached || timeoutReached) {
                flush();
            }
        } finally {
            lock.unlock();
        }
    }

    private void flush() {
        if (eventBuffer.isEmpty() || listener == null) {
            lastFlushMs = System.currentTimeMillis();
            return;
        }
        List<Event> events = new ArrayList<>(eventBuffer);
        List<Message> msgs = new ArrayList<>(messageBuffer);
        try {
            listener.onBatch(events);
            // 成功 -> 逐条 ack
            for (Message m : msgs) {
                m.acknowledge();
            }
        } catch (RuntimeException e) {
            // 下游失败 -> 不 ack, MQ 重投整批
            LOG.warn("listener.onBatch failed, batch will be redelivered ({} events)", events.size(), e);
            throw e;
        } finally {
            eventBuffer.clear();
            messageBuffer.clear();
            lastFlushMs = System.currentTimeMillis();
        }
    }

    private static String rawOf(final Message message) {
        if (message instanceof TextMessage tm) {
            try {
                return tm.getText();
            } catch (JMSException ignored) {
                return "<unreadable>";
            }
        }
        return "<non-text>";
    }
}
```

> 注：`flush()` 在 `onMessage` 的锁内同步执行（v1 简单做法，避免额外调度线程）。timeout 分支只在有新消息到达时才被检查 —— 长时间无消息时不会主动 flush，但此时 buffer 本就空，无影响。真实部署若需"无消息也按超时 flush"，再加独立调度线程。

- [ ] **Step 2: 编译**

Run: `mvn -q -pl observe-bootstrap -am compile`
Expected: BUILD SUCCESS（依赖 Task 6 的 IBM MQ 依赖）。

- [ ] **Step 3: Commit**

```bash
git add observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/source/IbmMqCdcSource.java
git commit -m "feat(bootstrap): add IbmMqCdcSource (JMS consumer, at-least-once, batched)"
```

---

## Task 8: 新增 IbmMqProperties（配置绑定）

**Files:**
- Create: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/config/IbmMqProperties.java`

- [ ] **Step 1: 写类**

```java
package com.imsw.observe.bootstrap.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "observe.worker.ibm-mq")
public class IbmMqProperties {

    private boolean enabled = false;

    private String host;

    private int port = 1414;

    private String queueManager;

    private String channel;

    private String queue;

    private int batchSize = 50;

    private long batchTimeoutMillis = 200L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(final String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(final int port) {
        this.port = port;
    }

    public String getQueueManager() {
        return queueManager;
    }

    public void setQueueManager(final String queueManager) {
        this.queueManager = queueManager;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(final String channel) {
        this.channel = channel;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(final String queue) {
        this.queue = queue;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(final int batchSize) {
        this.batchSize = batchSize;
    }

    public long getBatchTimeoutMillis() {
        return batchTimeoutMillis;
    }

    public void setBatchTimeoutMillis(final long batchTimeoutMillis) {
        this.batchTimeoutMillis = batchTimeoutMillis;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/config/IbmMqProperties.java
git commit -m "feat(bootstrap): add IbmMqProperties config binding"
```

---

## Task 9: 改造 WorkerConfig —— 删 cdcMqSource、接 inMemoryCdcSource、加 ibmMqCdcSource

**Files:**
- Modify: `observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/config/WorkerConfig.java`

改动：
1. 删 import：`CdcMqSource`、（旧）`InMemoryCdcMessageSource`。
2. 加 import：`InMemoryCdcSource`、`IbmMqCdcSource`、`IbmMqXmlParser`、`IbmMqProperties`、`jakarta.jms.ConnectionFactory` 或 `javax.jms.ConnectionFactory`（IBM MQ 客户端提供 `javax.jms`，用 `javax.jms.ConnectionFactory`）、`com.ibm.mq.jms.MQConnectionFactory`、`com.ibm.mq.MQException`（按需）。
3. 把 `cdcMessageSource()` bean 改为 `inMemoryCdcSource()`，返回 `new InMemoryCdcSource()`；在 `cdcSource` 之外新增一个把它接到 dispatcher 的装配。
4. 删除 `cdcMqSource(...)` bean。
5. 新增 `ibmMqCdcSource(...)` bean，`@ConditionalOnProperty(prefix="observe.worker.ibm-mq", name="enabled", havingValue="true")`。
6. 内存来源 bean 也要 `start(dispatcher::onBatch)`，否则 `EndToEndFlowTest` 的 `push()` 不生效。

> 关于 IBM MQ `ConnectionFactory` 构造：用 `com.ibm.mq.jms.MQConnectionFactory`，set host/port/queueManager/channel/transportType。本 Task 给出完整 bean 代码。

- [ ] **Step 1: 替换 import 与 bean**

打开 `WorkerConfig.java`，做如下修改：

a) 删除这两行 import：
```java
import com.imsw.observe.pipeline.infrastructure.source.CdcMqSource;
```
以及
```java
import com.imsw.observe.bootstrap.worker.source.InMemoryCdcMessageSource;
```

b) 新增 import：
```java
import com.imsw.observe.bootstrap.worker.config.IbmMqProperties;
import com.imsw.observe.bootstrap.worker.source.IbmMqCdcSource;
import com.imsw.observe.bootstrap.worker.source.IbmMqXmlParser;
import com.imsw.observe.bootstrap.worker.source.InMemoryCdcSource;
import com.ibm.mq.constants.CMQC;
import com.ibm.mq.jms.MQConnectionFactory;
import javax.jms.JMSException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
```

（`ConditionalOnProperty` 若已 import 则跳过。）

c) 删除旧的两个 bean：
```java
    @Bean
    public InMemoryCdcMessageSource cdcMessageSource() {
        return new InMemoryCdcMessageSource();
    }

    @Bean
    public CdcMqSource cdcMqSource(final InMemoryCdcMessageSource cdcMessageSource, final SourceDispatcher dispatcher) {
        CdcMqSource source = new CdcMqSource(cdcMessageSource);
        source.start(dispatcher::onBatch);
        return source;
    }
```

d) 新增两个 bean（替换上面删掉的位置）：
```java
    @Bean
    public InMemoryCdcSource inMemoryCdcSource(final SourceDispatcher dispatcher) {
        InMemoryCdcSource source = new InMemoryCdcSource();
        source.start(dispatcher::onBatch);
        return source;
    }

    @Bean
    @ConditionalOnProperty(prefix = "observe.worker.ibm-mq", name = "enabled", havingValue = "true")
    public IbmMqCdcSource ibmMqCdcSource(final SourceDispatcher dispatcher, final IbmMqProperties props) {
        MQConnectionFactory cf = new MQConnectionFactory();
        try {
            cf.setHostName(props.getHost());
            cf.setPort(props.getPort());
            cf.setQueueManager(props.getQueueManager());
            cf.setChannel(props.getChannel());
            cf.setTransportType(CMQC.TRANSPORT_MQSERIES_CLIENT);
        } catch (JMSException e) {
            throw new IllegalStateException("cannot configure IBM MQ connection factory", e);
        }
        IbmMqXmlParser parser = new IbmMqXmlParser();
        IbmMqCdcSource source = new IbmMqCdcSource(
                cf, props.getQueue(), parser, props.getBatchSize(), props.getBatchTimeoutMillis());
        source.start(dispatcher::onBatch);
        return source;
    }
```

> 注：IBM MQ 的 `MQConnectionFactory` 具体 setter / `CMQC.TRANSPORT_MQSERIES_CLIENT` 常量名依实际客户端版本而定；若编译报错，按 `com.ibm.mq.allclient` 9.4.x 的实际 API 调整（这一步在实现时验证）。

- [ ] **Step 2: 编译 bootstrap**

Run: `mvn -q -pl observe-bootstrap -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add observe-bootstrap/src/main/java/com/imsw/observe/bootstrap/worker/config/WorkerConfig.java
git commit -m "refactor(bootstrap): wire IbmMqCdcSource, merge away CdcMqSource"
```

---

## Task 10: 删除 CdcMessageSource 接口与 CdcMqSource 类（pipeline 层）

合并后这两个文件不再有任何引用（bootstrap 已不再 import）。

**Files:**
- Delete: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/CdcMessageSource.java`
- Delete: `observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/CdcMqSource.java`

- [ ] **Step 1: 确认无残留引用**

Run: `grep -rn "CdcMessageSource\|CdcMqSource" --include="*.java" .`
Expected: 无输出（所有引用已在 Task 5/9 清除）。若有残留，先清除。

- [ ] **Step 2: 删除文件**

```bash
git rm observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/CdcMessageSource.java \
       observe-pipeline/src/main/java/com/imsw/observe/pipeline/infrastructure/source/CdcMqSource.java
```

- [ ] **Step 3: 编译全量**

Run: `mvn -q -pl observe-pipeline -am compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(pipeline): remove merged-away CdcMessageSource and CdcMqSource"
```

---

## Task 11: application.yml 加 ibm-mq 段

**Files:**
- Modify: `observe-bootstrap/src/main/resources/application.yml`

- [ ] **Step 1: 在 observe.worker 段下追加 ibm-mq**

在 `application.yml` 的 `observe:` → `worker:` 下（`cron-period-millis` 之后）追加：

```yaml
    ibm-mq:
      enabled: false
      host: localhost
      port: 1414
      queue-manager: QM1
      channel: DEV.APP.SVRCONN
      queue: APP.Q
      batch-size: 50
      batch-timeout-millis: 200
```

> 默认 `enabled: false`，本地/CI 不连真实 MQ。需要时改 true 并填实际连接信息。

- [ ] **Step 2: Commit**

```bash
git add observe-bootstrap/src/main/resources/application.yml
git commit -m "feat(bootstrap): add observe.worker.ibm-mq config section"
```

---

## Task 12: 整体验证（全量编译 + 全量测试）

**Files:** 无改动

- [ ] **Step 1: 全量编译 + 测试**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS。关键：
- `IbmMqXmlParserTest` 4 用例通过。
- `EndToEndFlowTest` 通过（验证合并后 `InMemoryCdcSource` + `push()` 链路仍通）。
- 既有所有测试不回归。

- [ ] **Step 2: 若有 spotless/checkstyle 报错，修正**

Run: `mvn -q spotless:apply checkstyle:check -pl observe-bootstrap,observe-pipeline,observe-kernel` （按实际插件配置）
修正后：

```bash
git add -A
git commit -m "style: apply spotless/checkstyle after IBM MQ source integration"
```

- [ ] **Step 3: （可选，需真实 MQ）端到端验证**

在有 IBM MQ 的环境：把 `observe.worker.ibm-mq.enabled` 改 true、填连接信息，启动应用，向队列放一条 XML 消息，观察日志 `IbmMqCdcSource started` + 事件进入流水线。

---

## 完成标准

- `MessageParser<M>` 接口 + `MessageParseException` 就位。
- `IbmMqXmlParser` 通过 4 个单测（真实映射规则用户可后续调整）。
- `IbmMqCdcSource` 实现 JMS 消费 + 攒批 + at-least-once（`CLIENT_ACKNOWLEDGE`，ack 边界 = `onBatch` 成功，解析失败不 ack）。
- `IbmMqProperties` + `WorkerConfig` 条件装配（`ibm-mq.enabled=false` 默认不启用）。
- `CdcMessageSource` / `CdcMqSource` 已删除；`InMemoryCdcMessageSource` → `InMemoryCdcSource implements Source`。
- `EndToEndFlowTest` 通过（合并未破坏既有链路）。
- 全量 `mvn clean test` BUILD SUCCESS。
