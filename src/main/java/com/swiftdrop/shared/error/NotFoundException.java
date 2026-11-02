package com.swiftdrop.shared.error;

/** 404 - a resource that does not exist (e.g. NOT_FOUND). The status and default message come from the {@link ErrorCode}. */
public class NotFoundException extends ApiException {

    public NotFoundException(ErrorCode code) {
        super(code);
    }

    public NotFoundException(ErrorCode code, String message) {
        super(code, message);
    }
}
