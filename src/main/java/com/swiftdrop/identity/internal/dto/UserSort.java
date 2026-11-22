package com.swiftdrop.identity.internal.dto;

import org.springframework.data.domain.Sort;

/** The {@code sort} parameter of GET /admin/users. */
public enum UserSort {
    NEWEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
    OLDEST(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))),
    NAME(Sort.by(Sort.Order.asc("fullName").ignoreCase(), Sort.Order.asc("id")));

    private final Sort sort;

    UserSort(Sort sort) {
        this.sort = sort;
    }

    public Sort sort() {
        return sort;
    }
}
