package com.imsw.observe.pipeline.application;

import com.imsw.observe.kernel.event.model.SourceType;

public interface Source {

    SourceType type();

    void start(EventListener listener);

    void stop();
}
