package com.imsw.observe.bootstrap.worker.source;

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

import com.imsw.observe.kernel.error.MessageParseException;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.application.EventListener;
import com.imsw.observe.pipeline.application.Source;
import com.imsw.observe.pipeline.infrastructure.source.MessageParser;

/**
 * IBM MQ CDC 来源（B9 §3.3）。worker 作为 JMS consumer 消费队列，CLIENT_ACKNOWLEDGE 实现
 * at-least-once。ack 边界 = {@code listener.onEvent} 成功返回（事件已进入 SourceDispatcher 队列）。
 *
 * <p><b>反压</b>：{@link EventListener#onEvent} 阻塞入队（队列满）→ {@code onMessage} 阻塞 →
 * 不 ack → MQ 重投/暂停投递。
 *
 * <p><b>逐条 ack</b>：每条消息解析后单独回调 {@code onEvent} + {@code acknowledge}（不再攒批）。
 * pipeline 异步执行失败不回 MQ（沿用现状）。
 */
public final class IbmMqCdcSource implements Source, MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(IbmMqCdcSource.class);

    private final ConnectionFactory connectionFactory;

    private final String queueName;

    private final MessageParser<TextMessage> parser;

    private volatile boolean stopped = false;

    private EventListener listener;

    private Connection connection;

    private Session session;

    private MessageConsumer consumer;

    public IbmMqCdcSource(
            final ConnectionFactory connectionFactory,
            final String queueName,
            final MessageParser<TextMessage> parser) {
        this.connectionFactory = connectionFactory;
        this.queueName = queueName;
        this.parser = parser;
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
            LOG.info("IbmMqCdcSource started, queue={}", queueName);
        } catch (JMSException e) {
            closeQuietly(consumer);
            closeQuietly(session);
            closeQuietly(connection);
            consumer = null;
            session = null;
            connection = null;
            throw new IllegalStateException("cannot start IBM MQ CDC source", e);
        }
    }

    @Override
    public void stop() {
        stopped = true;
        closeQuietly(consumer);
        closeQuietly(session);
        closeQuietly(connection);
        consumer = null;
        session = null;
        connection = null;
        LOG.info("IbmMqCdcSource stopped");
    }

    @Override
    public void onMessage(final Message message) {
        if (stopped) {
            return;
        }
        // JMS 单 session 的 MessageListener 本就串行；onEvent 阻塞即反压到 MQ（不 ack → 重投/暂停）。
        Event event;
        try {
            event = parser.parse((TextMessage) message);
        } catch (MessageParseException e) {
            // 不 ack -> MQ 重投. 记日志(含原始文本)便于排查.
            LOG.error("parse failed, will redeliver; raw={}", rawOf(message), e);
            return;
        }
        // 阻塞入队（队列满时反压）；onEvent 返回即事件已入 SourceDispatcher 队列，可安全 ack。
        listener.onEvent(event);
        try {
            message.acknowledge();
        } catch (JMSException e) {
            // ack 失败：MQ 视为未 ack，将重投（at-least-once，可能产生重复）。
            LOG.warn("acknowledge failed, message may be redelivered", e);
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

    private static void closeQuietly(final MessageConsumer c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (JMSException e) {
            LOG.warn("error closing JMS consumer", e);
        }
    }

    private static void closeQuietly(final Session s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (JMSException e) {
            LOG.warn("error closing JMS session", e);
        }
    }

    private static void closeQuietly(final Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (JMSException e) {
            LOG.warn("error closing JMS connection", e);
        }
    }
}
