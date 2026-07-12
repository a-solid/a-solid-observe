package com.imsw.observe.kernel.script.spi;

public interface GroovyScriptEngine {

    Object execute(String scriptSource, ScriptContext ctx);

    void compile(String scriptSource);
}
