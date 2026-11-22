package com.swiftdrop.dispatch.internal.dto;

import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One courier task with its parcels. */
public record AssignmentDetailResponse(
        UUID id,
        AssignmentType type,
        AssignmentStatus status,
        CourierRef courier,
        District originBranch,
        District destinationBranch,
        LocalDate scheduledFor,
        String note,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Instant cancelledAt,
        List<AssignmentItemResponse> items,
        long version) {
}
