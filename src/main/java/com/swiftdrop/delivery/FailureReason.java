package com.swiftdrop.delivery;

/**
 * Why a delivery failed (status FAILED). Stored with the status change and sent as its name in
 * {@link ParcelStatusChanged#failureReason()}. {@link #OTHER} needs a note that explains what happened.
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier).
 */
public enum FailureReason {
    /** Nobody was there to receive the parcel. */
    RECIPIENT_UNAVAILABLE,
    /** The address doesn't exist or doesn't match the recipient. */
    WRONG_ADDRESS,
    /** The recipient refused the parcel. */
    RECIPIENT_REFUSED,
    /** The address couldn't be reached (road closed, gated, flooded). */
    ADDRESS_UNREACHABLE,
    /** The parcel was damaged and couldn't be handed over. */
    PARCEL_DAMAGED,
    /** Anything else; the note says what. */
    OTHER
}
