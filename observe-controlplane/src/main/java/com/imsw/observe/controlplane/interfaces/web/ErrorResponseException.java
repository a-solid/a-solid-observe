package com.imsw.observe.controlplane.interfaces.web;

import org.springframework.http.HttpStatus;

/**
 * 携带 {@link ErrorCode} + HTTP 状态的通用业务异常。
 *
 * <p>供业务层抛业务错误（非法状态转移、冲突等），{@link GlobalExceptionHandler} 据此返回对应状态码 + {@link ErrorBody}。
 * B7 ack/resolve 的非法转移（CONFLICT）等场景复用本类。
 */
public class ErrorResponseException extends RuntimeException {

    private final HttpStatus status;

    private final ErrorCode code;

    public ErrorResponseException(final HttpStatus status, final ErrorCode code, final String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ErrorResponseException(
            final HttpStatus status, final ErrorCode code, final String message, final Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
