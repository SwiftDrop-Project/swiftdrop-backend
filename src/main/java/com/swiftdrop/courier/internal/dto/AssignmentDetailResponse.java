package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierViews.AssignmentDetail;
import com.swiftdrop.courier.internal.dto.AssignmentSummaryResponse.CountsResponse;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** One courier task with its parcels: the summary fields plus {@code items}, by sequence. */
public record AssignmentDetailResponse(
        UUID assignmentId,
        AssignmentType type,
        AssignmentStatus status,
        District originBranch,
        District destinationBranch,
        LocalDate scheduledFor,
        String note,
        Instant assignedAt,
        Instant completedAt,
        CountsResponse counts,
        boolean canComplete,
        List<AssignmentItemResponse> items) {

    public static AssignmentDetailResponse from(AssignmentDetail detail) {
        AssignmentView assignment = detail.assignment();
        List<AssignmentItemResponse> items = assignment.items().stream()
                .sorted(Comparator.comparingInt(AssignmentItemView::sequence))
                .filter(item -> detail.parcels().containsKey(item.parcelId()))
                .map(item -> {
                    DeliverySnapshot parcel = detail.parcels().get(item.parcelId());
                    return AssignmentItemResponse.from(assignment, item, parcel);
                })
                .toList();
        return new AssignmentDetailResponse(assignment.id(), assignment.type(), assignment.status(),
                assignment.originBranch(), assignment.destinationBranch(), assignment.scheduledFor(),
                assignment.note(), assignment.assignedAt(), assignment.completedAt(),
                CountsResponse.from(assignment.items()), AssignmentSummaryResponse.canComplete(assignment), items);
    }
}
