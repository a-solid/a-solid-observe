package com.imsw.observe.kernel.event.model;

import java.util.Map;

import com.imsw.observe.kernel.alert.model.AlertSignal;

public interface ExecutionContext {

    ExecutionMeta meta();

    ExecutionData data();

    void putOutput(String key, Object value);

    Object getOutput(String key);

    <T> T getOutput(String key, Class<T> type);

    <T> T getNodeOutput(String nodeName, String key, Class<T> type);

    Map<String, Map<String, Object>> nodeOutputs();

    void emitAlert(AlertSignal signal);
}
