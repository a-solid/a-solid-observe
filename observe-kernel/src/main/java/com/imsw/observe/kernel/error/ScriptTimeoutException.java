package com.imsw.observe.kernel.error;

public class ScriptTimeoutException extends ObserveException {

    public ScriptTimeoutException(final String message) {
        super(message);
    }

    public ScriptTimeoutException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
