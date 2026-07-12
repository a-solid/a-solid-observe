package com.imsw.observe.pipeline.infrastructure.source;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.application.EventListener;
import com.imsw.observe.pipeline.application.Source;

public final class ApiSource implements Source {

    private static final Logger LOG = LoggerFactory.getLogger(ApiSource.class);

    private EventListener listener;

    @Override
    public SourceType type() {
        return SourceType.API;
    }

    @Override
    public void start(final EventListener listener) {
        this.listener = listener;
        LOG.info("ApiSource started");
    }

    @Override
    public void stop() {
        listener = null;
        LOG.info("ApiSource stopped");
    }

    public void submit(final Event event) {
        if (listener == null) {
            throw new IllegalStateException("ApiSource not started");
        }
        listener.onBatch(List.of(event));
    }
}
