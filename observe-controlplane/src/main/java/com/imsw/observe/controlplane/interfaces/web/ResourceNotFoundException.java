package com.imsw.observe.controlplane.interfaces.web;

/**
 * 资源不存在 / namespace 软隔离不匹配时抛出，{@link GlobalExceptionHandler} 映射为 404 + {@link ErrorBody}。
 *
 * <p>替代旧的手写 {@code ResponseEntity.notFound().build()}（空 body），让 404 也带机器可读错误体。
 */
public class ResourceNotFoundException extends RuntimeException {

    private final ErrorCode code;

    public ResourceNotFoundException(final String message) {
        this(ErrorCode.NOT_FOUND, message);
    }

    public ResourceNotFoundException(final ErrorCode code, final String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
