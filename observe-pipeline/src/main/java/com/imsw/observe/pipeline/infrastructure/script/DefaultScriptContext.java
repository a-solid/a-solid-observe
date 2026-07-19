package com.imsw.observe.pipeline.infrastructure.script;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.imsw.observe.kernel.script.spi.ScriptContext;
import com.imsw.observe.kernel.util.TypeConverter;

/**
 * ScriptContext 默认实现（单 space）。
 *
 * <p>历史版本曾分 workingSpace（脚本 set 写入）与 globalSpace（engine bindings：event/alerts/db/now）
 * 两套 map，但行为等价（没人 shadow bindings，{@code get} 先 working 再 global 的区分无实际意义），
 * 合一为单 map（simplify-contexts 重构）。{@link #putGlobal} 保留为 ScriptNode 初始化 bindings 的入口，
 * 与 {@link #set} 等价（都写同一 map），语义上区分"平台注入"vs"脚本写入"仅作注释意图。
 */
public final class DefaultScriptContext implements ScriptContext {

    private final Map<String, Object> vars = new HashMap<>();

    @Override
    public Object get(final String key) {
        return vars.get(key);
    }

    @Override
    public <T> T get(final String key, final Class<T> type) {
        return TypeConverter.convert(get(key), type);
    }

    @Override
    public void set(final String key, final Object value) {
        vars.put(key, value);
    }

    @Override
    public Set<String> keys() {
        return Set.copyOf(vars.keySet());
    }

    /** 平台注入 binding（event/alerts/db/now）；与 {@link #set} 等价，名取其意。 */
    public void putGlobal(final String key, final Object value) {
        vars.put(key, value);
    }
}
