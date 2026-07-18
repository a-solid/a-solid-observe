package com.imsw.observe.bootstrap.worker.source;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.application.EventListener;
import com.imsw.observe.pipeline.application.Source;

/**
 * 内存 CDC 来源（demo/测试用）。start 后, 外部调 push() 把事件喂进流水线。
 */
public final class InMemoryCdcSource implements Source {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryCdcSource.class);

    private EventListener listener;

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
        if (listener == null || events == null || events.isEmpty()) {
            return;
        }
        // B9：单事件契约——逐条 onEvent（onEvent 内部阻塞入队，反压随事件传播）。
        for (Event event : events) {
            listener.onEvent(event);
        }
    }
}
