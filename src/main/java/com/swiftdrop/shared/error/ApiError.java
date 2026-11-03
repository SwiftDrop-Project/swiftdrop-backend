package com.swiftdrop.shared.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** The error shape from API.md. {@code fieldErrors} is only present for VALIDATION_FAILED. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, Map<String, String> fieldErrors) {

    public static ApiError of(ErrorCode code) {
        return of(code, code.defaultMessage());
    }

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(code.status().value(), code.name(), message, null);
    }

    public static ApiError validation(Map<String, String> fieldErrors) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ApiError(code.status().value(), code.name(), code.defaultMessage(),
                fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
