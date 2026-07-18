package com.imsw.observe.pipeline.domain.subscription;

import java.time.Duration;

public sealed interface Action permits Action.Run, Action.Schedule, Action.Cancel {

    record Run() implements Action {}

    record Schedule(Duration delay, String correlationKeyPath, Long pipelineId) implements Action {}

    record Cancel(String correlationKeyPath) implements Action {}
}
