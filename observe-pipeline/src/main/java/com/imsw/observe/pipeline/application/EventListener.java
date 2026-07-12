package com.imsw.observe.pipeline.application;

import java.util.List;

import com.imsw.observe.kernel.event.model.Event;

public interface EventListener {

    void onBatch(List<Event> events);
}
