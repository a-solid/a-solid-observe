package com.imsw.observe.kernel.script.spi;

import java.util.Set;

public interface ScriptContext {

    Object get(String key);

    <T> T get(String key, Class<T> type);

    void set(String key, Object value);

    Set<String> keys();
}
