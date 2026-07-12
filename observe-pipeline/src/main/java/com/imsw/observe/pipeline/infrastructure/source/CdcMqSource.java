package com.imsw.observe.pipeline.infrastructure.source;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.EventListener;
import com.imsw.observe.pipeline.application.Source;

public final class CdcMqSource implements Source {

    private static final Logger LOG = LoggerFactory.getLogger(CdcMqSource.class);

    private final CdcMessageSource messageSource;

    private EventListener listener;

    public CdcMqSource(final CdcMessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public SourceType type() {
        return SourceType.CDC;
    }

    @Override
    public void start(final EventListener listener) {
        this.listener = listener;
        messageSource.subscribe(this::onBatch);
        LOG.info("CdcMqSource started");
    }

    @Override
    public void stop() {
        messageSource.stop();
        LOG.info("CdcMqSource stopped");
    }

    private void onBatch(final List<Event> events) {
        if (listener == null || events == null || events.isEmpty()) {
            return;
        }
        listener.onBatch(events);
    }
}
