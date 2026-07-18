package com.imsw.observe.controlplane.interfaces.web;

import org.springframework.http.HttpStatus;

/**
 * 机器可读错误码（ADR-0002 软隔离铁律下统一错误响应）。
 *
 * <p>每个 code 映射一个 HTTP 状态；{@link GlobalExceptionHandler} 据此构造 {@link ErrorBody}。
 * 前端按 {@link #name()} 分支处理，{@link #message} 仅作人可读展示。
 */
public enum ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND),
    VALIDATION(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    CONFLICT(HttpStatus.CONFLICT),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(final HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
