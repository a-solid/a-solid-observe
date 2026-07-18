package com.imsw.observe.controlplane.interfaces.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 分页请求清洗 + {@link Page} 元素映射工具（control-plane web 层共用）。
 *
 * <p>1-based {@code page}（前端友好）→ 0-based Spring {@link Pageable}；{@code size} 缺省走 {@code limit} 别名；
 * 越界/非法值回落默认；封顶 {@link #MAX_SIZE} 防超大查询。
 */
public final class Pages {

    public static final int DEFAULT_SIZE = 20;

    public static final int MAX_SIZE = 500;

    private Pages() {}

    public static Pageable pageable(final int page, final Integer size, final Integer limit) {
        return PageRequest.of(Math.max(page, 1) - 1, sanitizeSize(size, limit));
    }

    public static int sanitizeSize(final Integer size, final Integer limit) {
        int resolved = size != null ? size : (limit != null ? limit : DEFAULT_SIZE);
        if (resolved <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(resolved, MAX_SIZE);
    }

    /** 把 {@code Page<S>} 映射成 {@code PageResponse<T>}，保留 total 与分页元信息。 */
    public static <S, T> PageResponse<T> toResponse(
            final Page<S> source,
            final Function<S, T> mapper,
            final int page,
            final Integer size,
            final Integer limit) {
        List<T> data = source.getContent().stream().map(mapper).toList();
        int sanitizedSize = sanitizeSize(size, limit);
        return PageResponse.of(data, Math.max(page, 1), sanitizedSize, source.getTotalElements());
    }
}
