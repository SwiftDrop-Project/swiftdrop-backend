package com.swiftdrop.shared.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** The JSON shape for paged lists: the items of one page plus the paging numbers (page is 0-based). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page.map(mapper::apply));
    }
}
