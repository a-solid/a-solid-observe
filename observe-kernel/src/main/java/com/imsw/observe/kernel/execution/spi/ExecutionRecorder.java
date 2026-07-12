package com.imsw.observe.kernel.execution.spi;

import java.time.Duration;

import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.execution.model.ErrorType;

public interface ExecutionRecorder {

    void recordSuccess(
            ExecutionContext ctx, String outcome, Duration duration, boolean emittedAlert, double sampleRatio);

    void recordFailure(ExecutionContext ctx, Throwable error, Duration duration, String nodeName, ErrorType errorType);
}
