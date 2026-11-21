package com.swiftdrop.shared.error;

/**
 * 429 TOO_MANY_REQUESTS from a service, with how long to wait. {@code GlobalExceptionHandler} sends it as the
 * {@code Retry-After} header (whole seconds), which CORS exposes so the frontend can say "Try again in 8 seconds".
 */
public class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    /** {@code retryAfterSeconds} below 1 is sent as 1. */
    public TooManyRequestsException(long retryAfterSeconds) {
        super(ErrorCode.TOO_MANY_REQUESTS);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
