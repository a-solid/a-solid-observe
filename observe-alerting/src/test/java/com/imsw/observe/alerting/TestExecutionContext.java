package com.imsw.observe.alerting;

import java.util.LinkedHashMap;
import java.util.Map;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionData;
import com.imsw.observe.kernel.event.model.ExecutionMeta;

final class TestExecutionContext implements ExecutionContext {

    private final ExecutionMeta meta;

    private final ExecutionData data;

    private final Map<String, Map<String, Object>> outputs = new LinkedHashMap<>();

    TestExecutionContext(final ExecutionMeta meta, final ExecutionData data) {
        this.meta = meta;
        this.data = data;
    }

    void putNodeOutput(final String node, final String key, final Object value) {
        outputs.computeIfAbsent(node, k -> new LinkedHashMap<>()).put(key, value);
    }

    @Override
    public ExecutionMeta meta() {
        return meta;
    }

    @Override
    public ExecutionData data() {
        return data;
    }

    @Override
    public void putOutput(final String key, final Object value) {}

    @Override
    public Object getOutput(final String key) {
        return null;
    }

    @Override
    public <T> T getOutput(final String key, final Class<T> type) {
        return null;
    }

    @Override
    public <T> T getNodeOutput(final String nodeName, final String key, final Class<T> type) {
        return null;
    }

    @Override
    public Map<String, Map<String, Object>> nodeOutputs() {
        return outputs;
    }

    @Override
    public void emitAlert(final AlertSignal signal) {
        data.alerts.add(signal);
    }
}
