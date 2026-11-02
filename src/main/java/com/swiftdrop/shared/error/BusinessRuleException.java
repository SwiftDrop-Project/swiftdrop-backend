package com.swiftdrop.shared.error;

/** a domain rule was broken (use an ErrorCode whose status fits, usually 409 or 422). The status and default message come from the {@link ErrorCode}. */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(ErrorCode code) {
        super(code);
    }

    public BusinessRuleException(ErrorCode code, String message) {
        super(code, message);
    }
}
