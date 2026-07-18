package com.imsw.observe.kernel.error;

/**
 * 领域层「资源不存在」异常（kernel 共用，controlplane {@code GlobalExceptionHandler} 映射为 404 NOT_FOUND）。
 *
 * <p>放 kernel 而非 controlplane：alerting/pipeline/config 等业务模块需抛此异常，而它们不依赖 controlplane。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(final String message) {
        super(message);
    }
}
