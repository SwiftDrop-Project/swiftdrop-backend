package com.swiftdrop.shared.error;

/**
 * Thrown by any layer to return a specific API.md error. The message is shown to the client.
 * Prefer the subclasses ({@link NotFoundException}, {@link ForbiddenException}, {@link ConflictException},
 * {@link BusinessRuleException}); use this class directly for 401 codes.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
