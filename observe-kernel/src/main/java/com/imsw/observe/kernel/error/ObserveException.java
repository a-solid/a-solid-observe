package com.imsw.observe.kernel.error;

public class ObserveException extends RuntimeException {

    public ObserveException(final String message) {
        super(message);
    }

    public ObserveException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
