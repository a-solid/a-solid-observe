package com.imsw.observe.kernel.error;

public class ScriptExecutionException extends ObserveException {

    public ScriptExecutionException(final String message) {
        super(message);
    }

    public ScriptExecutionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
