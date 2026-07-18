package com.imsw.observe.controlplane.interfaces.web;

/**
 * 资源不存在 / namespace 软隔离不匹配时抛出，{@link GlobalExceptionHandler} 映射为 404 + {@link ErrorBody}。
 *
 * <p>继承 kernel 的 {@link com.imsw.observe.kernel.error.ResourceNotFoundException}：业务层（alerting 等，
 * 不依赖 controlplane）抛 kernel 版本，controlplane 的 handler 统一捕获二者。
 */
public class ResourceNotFoundException extends com.imsw.observe.kernel.error.ResourceNotFoundException {

    public ResourceNotFoundException(final String message) {
        super(message);
    }
}
