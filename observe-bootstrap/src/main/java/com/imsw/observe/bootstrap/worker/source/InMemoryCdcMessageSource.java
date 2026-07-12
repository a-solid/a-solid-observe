package com.imsw.observe.bootstrap.worker.source;

import java.util.List;
import java.util.function.Consumer;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.pipeline.infrastructure.source.CdcMessageSource;

public final class InMemoryCdcMessageSource implements CdcMessageSource {

    private Consumer<List<Event>> sink;

    @Override
    public void subscribe(final Consumer<List<Event>> onBatch) {
        this.sink = onBatch;
    }

    @Override
    public void stop() {
        this.sink = null;
    }

    public void push(final List<Event> events) {
        if (sink != null) {
            sink.accept(events);
        }
    }
}
