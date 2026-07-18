package com.imsw.observe.controlplane.interfaces.web;

/**
 * 单资源 / 无分页列表响应信封（{@code {data}}）。
 *
 * <p>分页列表用 {@link PageResponse}；错误由 {@link GlobalExceptionHandler} 统一成 {@link ErrorBody}。
 *
 * @param <T> 单资源为 DTO；无分页列表为 {@code List<DTO>}。
 */
public record ApiResponse<T>(T data) {

    public static <T> ApiResponse<T> ok(final T data) {
        return new ApiResponse<>(data);
    }
}
