package com.swiftdrop.tracking.internal.dto;

import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.Instant;

/**
 * One step of a parcel's journey on the public tracking page. Never the note or who made the change.
 *
 * @param branch        only for AT_BRANCH: the branch the parcel arrived at
 * @param failureReason only for FAILED: the fixed reason code (not free text)
 */
public record TrackingStep(ParcelStatus status, District branch, FailureReason failureReason, Instant occurredAt) {
}
