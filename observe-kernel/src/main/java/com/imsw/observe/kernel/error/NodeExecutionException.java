package com.imsw.observe.kernel.error;

public class NodeExecutionException extends ObserveException {

    private final String nodeName;

    public NodeExecutionException(final String nodeName, final String message) {
        super(message);
        this.nodeName = nodeName;
    }

    public NodeExecutionException(final String nodeName, final String message, final Throwable cause) {
        super(message, cause);
        this.nodeName = nodeName;
    }

    public String getNodeName() {
        return nodeName;
    }
}
