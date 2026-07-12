package com.imsw.observe.kernel.error;

public class DataSourceException extends ObserveException {

    public DataSourceException(final String message) {
        super(message);
    }

    public DataSourceException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
