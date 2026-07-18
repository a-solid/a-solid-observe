package com.imsw.observe.kernel.error;

/**
 * 领域层「状态/业务冲突」异常（kernel 共用，controlplane {@code GlobalExceptionHandler} 映射为 409 CONFLICT）。
 *
 * <p>例如告警非法状态转移、并发改写（CAS 影响 0 行）。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(final String message) {
        super(message);
    }
}
