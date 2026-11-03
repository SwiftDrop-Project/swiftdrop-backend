package com.swiftdrop.shared.error;

import java.util.Map;

/**
 * 400 VALIDATION_FAILED for a rule that Bean Validation cannot check (e.g. "current password is incorrect").
 * The response carries {@code fieldErrors} exactly like an invalid request body.
 */
public class FieldValidationException extends ApiException {

    private final Map<String, String> fieldErrors;

    public FieldValidationException(String field, String message) {
        super(ErrorCode.VALIDATION_FAILED);
        this.fieldErrors = Map.of(field, message);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
