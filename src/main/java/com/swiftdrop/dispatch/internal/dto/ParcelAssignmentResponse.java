package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.delivery.ParcelStatus;

import java.time.Instant;
import java.util.UUID;

/** Result of assigning a courier to a parcel. */
public record ParcelAssignmentResponse(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        UUID assignedCourierId,
        Instant assignedAt) {
}

