package com.swiftdrop.shared.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** The error shape from API.md. {@code fieldErrors} is only present for VALIDATION_FAILED. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(int status, String code, String message, Map<String, String> fieldErrors) {

    public static ErrorResponse of(ErrorCode code) {
        return of(code, code.defaultMessage());
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.status().value(), code.name(), message, null);
    }

    public static ErrorResponse validation(Map<String, String> fieldErrors) {
        ErrorCode code = ErrorCode.VALIDATION_FAILED;
        return new ErrorResponse(code.status().value(), code.name(), code.defaultMessage(),
                fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
