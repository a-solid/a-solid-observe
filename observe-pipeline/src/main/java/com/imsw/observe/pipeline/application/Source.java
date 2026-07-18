package com.imsw.observe.pipeline.application;

public interface Source {

    void start(EventListener listener);

    void stop();
}
