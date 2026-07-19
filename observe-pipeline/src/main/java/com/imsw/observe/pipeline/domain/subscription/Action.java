package com.imsw.observe.pipeline.domain.subscription;

import java.time.Duration;

public sealed interface Action permits Action.Run, Action.Schedule, Action.Cancel {

    record Run() implements Action {}

    // pipelineId 字段移除：扇出后 subscription 绑多 pipeline，延时目标由 dispatcher 按 (sub,pipeline) 上下文传入，
    // schedule 本身不再持有单 pipelineId。延时语义完整重设计见 delayed-redesign spec。
    record Schedule(Duration delay, String correlationKeyPath) implements Action {}

    record Cancel(String correlationKeyPath) implements Action {}
}
