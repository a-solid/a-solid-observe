package com.imsw.observe.pipeline.infrastructure.script;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.codehaus.groovy.control.CompilerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.error.ScriptCompilationException;
import com.imsw.observe.kernel.error.ScriptExecutionException;
import com.imsw.observe.kernel.error.ScriptTimeoutException;
import com.imsw.observe.kernel.script.spi.GroovyScriptEngine;
import com.imsw.observe.kernel.script.spi.ScriptContext;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;

public final class GroovyScriptEngineImpl implements GroovyScriptEngine {

    private static final Logger LOG = LoggerFactory.getLogger(GroovyScriptEngineImpl.class);

    private static final long TIMEOUT_MS = 5_000L;

    private static final List<String> IMPORTS_WHITELIST = List.of(
            "java.lang.Math",
            "java.util.Date",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.Map",
            "java.util.List",
            "java.util.Set");

    private static final List<String> RECEIVERS_BLACKLIST = List.of(
            "java.lang.System",
            "java.lang.Runtime",
            "java.lang.Thread",
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.lang.ProcessBuilder",
            "javax.script.ScriptEngine",
            "groovy.lang.GroovyClassLoader");

    private final CompilerConfiguration compilerConfig;
    private final Map<String, Class<?>> compiledCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdogExecutor;

    public GroovyScriptEngineImpl() {
        this.compilerConfig = new CompilerConfiguration();
        this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "groovy-watchdog");
            t.setDaemon(true);
            return t;
        });
        configureSandbox();
    }

    private void configureSandbox() {
        org.codehaus.groovy.control.customizers.SecureASTCustomizer sec;
        sec = new org.codehaus.groovy.control.customizers.SecureASTCustomizer();
        sec.setReceiversBlackList(RECEIVERS_BLACKLIST);
        compilerConfig.addCompilationCustomizers(sec);
    }

    @Override
    public Object execute(final String scriptSource, final ScriptContext ctx) {
        Class<?> scriptClass = compileToClass(scriptSource);
        Script script = newScript(scriptClass);
        Binding binding = new Binding();
        ctx.keys().forEach(k -> binding.setVariable(k, ctx.get(k)));
        script.setBinding(binding);

        Thread current = Thread.currentThread();
        Future<?> guard = watchdogExecutor.schedule(
                () -> {
                    LOG.warn("script timed out after {}ms, interrupting thread", TIMEOUT_MS);
                    current.interrupt();
                },
                TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
        try {
            return script.run();
        } catch (org.codehaus.groovy.control.CompilationFailedException e) {
            throw new ScriptCompilationException("Script failed to compile", e);
        } catch (Exception e) {
            if (isInterruptedRoot(e)) {
                Thread.currentThread().interrupt();
                throw new ScriptTimeoutException("Script exceeded " + TIMEOUT_MS + "ms timeout", e);
            }
            throw new ScriptExecutionException("Script execution failed: " + e.getMessage(), e);
        } finally {
            guard.cancel(true);
        }
    }

    @Override
    public void compile(final String scriptSource) {
        compile0(scriptSource);
    }

    private Class<?> compileToClass(final String source) {
        return compile0(source);
    }

    private Class<?> compile0(final String source) {
        return compiledCache.computeIfAbsent(source, s -> {
            try {
                GroovyClassLoader loader =
                        new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), compilerConfig);
                return loader.parseClass(s);
            } catch (org.codehaus.groovy.control.CompilationFailedException e) {
                throw new ScriptCompilationException("Script failed to compile", e);
            } catch (Exception e) {
                throw new ScriptCompilationException("Script failed to parse: " + e.getMessage(), e);
            }
        });
    }

    private Script newScript(final Class<?> scriptClass) {
        try {
            return (Script) scriptClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ScriptExecutionException("Failed to instantiate script class", e);
        }
    }

    private static boolean isInterruptedRoot(final Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof InterruptedException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
