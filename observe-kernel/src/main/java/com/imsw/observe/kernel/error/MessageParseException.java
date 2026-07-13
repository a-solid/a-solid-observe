package com.imsw.observe.kernel.error;

public class MessageParseException extends ObserveException {

    public MessageParseException(final String message) {
        super(message);
    }

    public MessageParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
