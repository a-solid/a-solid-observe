package com.imsw.observe.pipeline.infrastructure.script;

import java.util.ArrayList;
import java.util.List;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionMeta;

/**
 * ExecutionContext 默认实现（瘦身版）。
 *
 * <p>持有执行元信息 + 触发事件 + 累积的告警信号。{@link #scriptContext()} 暴露脚本运行时工作区
 * （仅 ScriptNode 用，非 ExecutionContext 接口契约——接口保持纯净，不塞脚本细节）。
 */
public final class DefaultExecutionContext implements ExecutionContext {

    private final ExecutionMeta meta;

    private final Event event;

    private final List<AlertSignal> alerts = new ArrayList<>();

    private final DefaultScriptContext scriptContext = new DefaultScriptContext();

    public DefaultExecutionContext(final ExecutionMeta meta, final Event event) {
        this.meta = meta;
        this.event = event;
    }

    /** 脚本运行时工作区（非接口契约，仅 ScriptNode 用）。 */
    public DefaultScriptContext scriptContext() {
        return scriptContext;
    }

    @Override
    public ExecutionMeta meta() {
        return meta;
    }

    @Override
    public Event event() {
        return event;
    }

    @Override
    public void emitAlert(final AlertSignal signal) {
        alerts.add(signal);
    }

    @Override
    public List<AlertSignal> drainAlerts() {
        List<AlertSignal> snapshot = List.copyOf(alerts);
        alerts.clear();
        return snapshot;
    }
}
