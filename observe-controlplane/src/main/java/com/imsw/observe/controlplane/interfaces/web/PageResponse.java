package com.imsw.observe.controlplane.interfaces.web;

import java.util.List;

/**
 * 分页列表响应信封（{@code {data,page}}）。
 *
 * @param <T> 列表元素 DTO 类型。
 */
public record PageResponse<T>(List<T> data, Page page) {

    public static <T> PageResponse<T> of(final List<T> data, final int page, final int size, final long total) {
        return new PageResponse<>(data, new Page(page, size, total));
    }
}
