package com.swiftdrop.webhooks.internal.client;

import com.swiftdrop.webhooks.internal.domain.RetryPolicy;

/**
 * The result of one attempt, as stored in webhooks.deliveries (last_status_code, last_error).
 *
 * @param statusCode the merchant's HTTP status, null when there was no answer
 * @param error      null on success, otherwise a short code from API.md ("HTTP_503", "TIMEOUT", ...); never a body
 */
public record DeliveryOutcome(Integer statusCode, String error) {

    public static final String TIMEOUT = "TIMEOUT";
    public static final String CONNECTION_FAILED = "CONNECTION_FAILED";
    public static final String URL_NOT_ALLOWED = "URL_NOT_ALLOWED";

    public static DeliveryOutcome answered(int statusCode) {
        return new DeliveryOutcome(statusCode, RetryPolicy.isSuccess(statusCode) ? null : "HTTP_" + statusCode);
    }

    public static DeliveryOutcome timeout() {
        return new DeliveryOutcome(null, TIMEOUT);
    }

    public static DeliveryOutcome connectionFailed() {
        return new DeliveryOutcome(null, CONNECTION_FAILED);
    }

    public static DeliveryOutcome urlNotAllowed() {
        return new DeliveryOutcome(null, URL_NOT_ALLOWED);
    }

    public boolean succeeded() {
        return RetryPolicy.isSuccess(statusCode);
    }

    public boolean retryable() {
        return !succeeded() && RetryPolicy.isRetryable(statusCode);
    }

    /** For logs: SUCCEEDED or the error code. */
    public String label() {
        return succeeded() ? "SUCCEEDED" : error;
    }
}
