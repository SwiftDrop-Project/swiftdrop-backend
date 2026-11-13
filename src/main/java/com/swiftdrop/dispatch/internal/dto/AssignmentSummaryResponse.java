package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One courier task in the dispatcher's list. */
public record AssignmentSummaryResponse(
        UUID id,
        AssignmentType type,
        AssignmentStatus status,
        CourierRef courier,
        District originBranch,
        District destinationBranch,
        LocalDate scheduledFor,
        int parcelCount,
        long openParcelCount,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt,
        long version) {
}
