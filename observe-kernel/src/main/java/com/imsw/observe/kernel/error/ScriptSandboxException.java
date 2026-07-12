package com.imsw.observe.kernel.error;

public class ScriptSandboxException extends ObserveException {

    public ScriptSandboxException(final String message) {
        super(message);
    }

    public ScriptSandboxException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
