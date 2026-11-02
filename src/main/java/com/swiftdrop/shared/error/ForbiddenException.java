package com.swiftdrop.shared.error;

/** 403 - logged in but not allowed (e.g. ACCOUNT_DISABLED, ACCOUNT_PENDING_APPROVAL). The status and default message come from the {@link ErrorCode}. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(ErrorCode code) {
        super(code);
    }

    public ForbiddenException(ErrorCode code, String message) {
        super(code, message);
    }
}
