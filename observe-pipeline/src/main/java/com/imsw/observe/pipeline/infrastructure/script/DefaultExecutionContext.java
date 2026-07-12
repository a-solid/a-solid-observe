package com.imsw.observe.pipeline.infrastructure.script;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionData;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.util.TypeConverter;

public final class DefaultExecutionContext implements ExecutionContext {

    private final ExecutionMeta meta;
    private final ExecutionData data;
    private final Map<String, Map<String, Object>> outputs = new HashMap<>();
    private final DefaultScriptContext scriptContext = new DefaultScriptContext();
    private String currentNode = "";

    public DefaultExecutionContext(final ExecutionMeta meta, final ExecutionData data) {
        this.meta = meta;
        this.data = data;
    }

    public DefaultScriptContext scriptContext() {
        return scriptContext;
    }

    @Override
    public Map<String, Map<String, Object>> nodeOutputs() {
        Map<String, Map<String, Object>> snapshot = new HashMap<>();
        outputs.forEach((node, kvs) -> snapshot.put(node, Collections.unmodifiableMap(new HashMap<>(kvs))));
        return Collections.unmodifiableMap(snapshot);
    }

    public void enterNode(final String name) {
        this.currentNode = name;
        outputs.computeIfAbsent(name, k -> new HashMap<>());
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
    public void putOutput(final String key, final Object value) {
        outputs.computeIfAbsent(currentNode, k -> new HashMap<>()).put(key, value);
    }

    @Override
    public Object getOutput(final String key) {
        Map<String, Object> nodeOutputs = outputs.get(currentNode);
        return nodeOutputs == null ? null : nodeOutputs.get(key);
    }

    @Override
    public <T> T getOutput(final String key, final Class<T> type) {
        return TypeConverter.convert(getOutput(key), type);
    }

    @Override
    public <T> T getNodeOutput(final String nodeName, final String key, final Class<T> type) {
        Map<String, Object> nodeOutputs = outputs.get(nodeName);
        if (nodeOutputs == null) {
            return null;
        }
        return TypeConverter.convert(nodeOutputs.get(key), type);
    }

    @Override
    public void emitAlert(final AlertSignal signal) {
        data.alerts.add(signal);
    }
}
