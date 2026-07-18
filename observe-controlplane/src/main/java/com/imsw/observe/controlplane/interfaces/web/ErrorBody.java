package com.imsw.observe.controlplane.interfaces.web;

/**
 * 统一错误响应体（{@code {error:{code,message,traceId}}}）。
 *
 * <p>所有错误（含 404）都走此形态；前端统一拦截器按 {@link #code()} 分支。
 */
public record ErrorBody(String code, String message, String traceId) {

    public static ErrorBody of(final ErrorCode code, final String message, final String traceId) {
        return new ErrorBody(code.name(), message, traceId);
    }
}
