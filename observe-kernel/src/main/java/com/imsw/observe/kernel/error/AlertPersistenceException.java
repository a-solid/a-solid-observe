package com.imsw.observe.kernel.error;

public class AlertPersistenceException extends ObserveException {

    public AlertPersistenceException(final String message) {
        super(message);
    }

    public AlertPersistenceException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
