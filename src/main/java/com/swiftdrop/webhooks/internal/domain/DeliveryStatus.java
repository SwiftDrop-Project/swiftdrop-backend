package com.swiftdrop.webhooks.internal.domain;

/** State of one webhook delivery (webhooks.deliveries.status). */
public enum DeliveryStatus {
    /** Waiting for its next attempt ({@code next_attempt_at} is set). */
    PENDING,
    /** The merchant answered 2xx ({@code delivered_at} is set). */
    SUCCEEDED,
    /** No more automatic attempts; the merchant or an admin can retry it. */
    FAILED
}
