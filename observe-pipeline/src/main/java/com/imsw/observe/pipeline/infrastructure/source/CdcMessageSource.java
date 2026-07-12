package com.imsw.observe.pipeline.infrastructure.source;

import java.util.List;
import java.util.function.Consumer;

import com.imsw.observe.kernel.event.model.Event;

public interface CdcMessageSource {

    void subscribe(Consumer<List<Event>> onBatch);

    void stop();
}
