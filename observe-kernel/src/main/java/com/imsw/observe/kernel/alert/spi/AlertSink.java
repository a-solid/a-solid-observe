package com.imsw.observe.kernel.alert.spi;

import com.imsw.observe.kernel.event.model.ExecutionContext;

public interface AlertSink {

    void drainAndPersist(ExecutionContext ctx);
}
