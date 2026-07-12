package com.imsw.observe.kernel.execution.model;

public enum ErrorType {
    SCRIPT_COMPILATION,
    SCRIPT_SANDBOX,
    SCRIPT_TIMEOUT,
    SCRIPT_EXECUTION,
    NODE_EXECUTION,
    PIPELINE_TIMEOUT,
    GRACEFUL_SHUTDOWN_KILL,
    UNKNOWN
}
