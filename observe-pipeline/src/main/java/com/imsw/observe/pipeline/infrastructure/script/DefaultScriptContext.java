package com.imsw.observe.pipeline.infrastructure.script;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.imsw.observe.kernel.script.spi.ScriptContext;
import com.imsw.observe.kernel.util.TypeConverter;

public final class DefaultScriptContext implements ScriptContext {

    private final Map<String, Object> workingSpace = new HashMap<>();
    private final Map<String, Object> globalSpace = new HashMap<>();

    @Override
    public Object get(final String key) {
        if (workingSpace.containsKey(key)) {
            return workingSpace.get(key);
        }
        return globalSpace.get(key);
    }

    @Override
    public <T> T get(final String key, final Class<T> type) {
        return TypeConverter.convert(get(key), type);
    }

    @Override
    public void set(final String key, final Object value) {
        workingSpace.put(key, value);
    }

    @Override
    public Set<String> keys() {
        Set<String> all = new HashSet<>();
        all.addAll(workingSpace.keySet());
        all.addAll(globalSpace.keySet());
        return all;
    }

    public void putGlobal(final String key, final Object value) {
        globalSpace.put(key, value);
    }
}
