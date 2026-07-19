package com.imsw.observe.alerting;

import java.util.ArrayList;
import java.util.List;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionMeta;

/** ExecutionContext 测试桩（瘦身接口版）。 */
final class TestExecutionContext implements ExecutionContext {

    private final ExecutionMeta meta;

    private final Event event;

    private final List<AlertSignal> alerts = new ArrayList<>();

    TestExecutionContext(final ExecutionMeta meta, final Event event) {
        this.meta = meta;
        this.event = event;
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
