package com.imsw.observe.kernel.error;

public class ScriptCompilationException extends ObserveException {

    public ScriptCompilationException(final String message) {
        super(message);
    }

    public ScriptCompilationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
