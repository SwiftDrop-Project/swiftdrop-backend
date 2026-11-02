package com.swiftdrop.shared.error;

/** 409 - clashes with existing data (e.g. EMAIL_ALREADY_REGISTERED). The status and default message come from the {@link ErrorCode}. */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code) {
        super(code);
    }

    public ConflictException(ErrorCode code, String message) {
        super(code, message);
    }
}
