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

import com.imsw.observe.kernel.error.MessageParseException;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
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
            try {
                listener.onBatch(events);
            } catch (RuntimeException e) {
                // 下游失败 -> 不 ack, MQ 重投整批
                LOG.warn("listener.onBatch failed, batch will be redelivered ({} events)", events.size(), e);
                throw e;
            }
            // 成功 -> 逐条 ack. 单条 ack 异常不影响已派发批次, 仅记日志; 未成功 ack 的消息会被 MQ 重投.
            for (Message m : msgs) {
                try {
                    m.acknowledge();
                } catch (JMSException e) {
                    LOG.warn("acknowledge failed, message may be redelivered", e);
                }
            }
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
