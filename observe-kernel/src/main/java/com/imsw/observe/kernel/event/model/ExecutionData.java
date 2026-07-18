package com.imsw.observe.kernel.event.model;

import java.util.ArrayList;
import java.util.List;

import com.imsw.observe.kernel.alert.model.AlertSignal;

public class ExecutionData {

    public Event event;
    public List<AlertSignal> alerts;
    public boolean emittedAlert;

    public ExecutionData() {
        this.alerts = new ArrayList<>();
    }

    public ExecutionData(final Event event) {
        this();
        this.event = event;
    }

    public List<AlertSignal> drainNewAlerts() {
        List<AlertSignal> snapshot = List.copyOf(alerts);
        alerts.clear();
        return snapshot;
    }
}
