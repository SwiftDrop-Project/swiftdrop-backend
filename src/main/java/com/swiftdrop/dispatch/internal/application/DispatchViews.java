package com.swiftdrop.dispatch.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.dispatch.internal.domain.AssignmentItem;
import com.swiftdrop.dispatch.internal.domain.CourierAssignment;
import com.swiftdrop.dispatch.internal.dto.AssignmentDetailResponse;
import com.swiftdrop.dispatch.internal.dto.AssignmentItemResponse;
import com.swiftdrop.dispatch.internal.dto.AssignmentSummaryResponse;
import com.swiftdrop.dispatch.internal.dto.CourierRef;
import com.swiftdrop.dispatch.internal.dto.PlaceResponse;
import com.swiftdrop.dispatch.internal.dto.QueueParcelResponse;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserSummary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the dispatcher's responses. Couriers and parcels are read in one call per list (identity and delivery), never
 * one per row.
 */
@Component
class DispatchViews {

    private final IdentityApi identity;
    private final DeliveryApi deliveries;

    DispatchViews(IdentityApi identity, DeliveryApi deliveries) {
        this.identity = identity;
        this.deliveries = deliveries;
    }

    /** The couriers with these ids. An unknown id gets a CourierRef without a name. */
    Map<UUID, CourierRef> couriers(Collection<UUID> courierIds) {
        if (courierIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UserSummary> users = identity.findAllById(new HashSet<>(courierIds)).stream()
                .collect(Collectors.toMap(UserSummary::id, Function.identity(), (a, b) -> a));
        return courierIds.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> {
            UserSummary user = users.get(id);
            return user == null ? new CourierRef(id, null, null) : new CourierRef(id, user.fullName(), user.phone());
        }));
    }

    /** The parcels with these ids, by id. Unknown ids are left out. */
    Map<UUID, DeliverySnapshot> parcels(Collection<UUID> parcelIds) {
        if (parcelIds.isEmpty()) {
            return Map.of();
        }
        return deliveries.findAllById(new HashSet<>(parcelIds)).stream()
                .collect(Collectors.toMap(DeliverySnapshot::id, Function.identity(), (a, b) -> a));
    }

    AssignmentDetailResponse detail(CourierAssignment assignment) {
        CourierRef courier = couriers(List.of(assignment.getCourierId())).get(assignment.getCourierId());
        Map<UUID, DeliverySnapshot> parcels = parcels(
                assignment.getItems().stream().map(AssignmentItem::getParcelId).toList());
        List<AssignmentItemResponse> items = assignment.getItems().stream()
                .map(item -> item(item, parcels.get(item.getParcelId())))
                .toList();
        return new AssignmentDetailResponse(assignment.getId(), assignment.getType(), assignment.getStatus(), courier,
                assignment.getOriginBranch(), assignment.getDestinationBranch(), assignment.getScheduledFor(),
                assignment.getNote(), assignment.getCreatedAt(), assignment.getStartedAt(),
                assignment.getCompletedAt(), assignment.getCancelledAt(), items, version(assignment));
    }

    List<AssignmentSummaryResponse> summaries(List<CourierAssignment> list) {
        Set<UUID> courierIds = list.stream().map(CourierAssignment::getCourierId).collect(Collectors.toSet());
        Map<UUID, CourierRef> couriers = couriers(courierIds);
        return list.stream().map(a -> new AssignmentSummaryResponse(a.getId(), a.getType(), a.getStatus(),
                couriers.get(a.getCourierId()), a.getOriginBranch(), a.getDestinationBranch(), a.getScheduledFor(),
                a.getItems().size(), a.openItemCount(), a.getCreatedAt(), a.getStartedAt(), a.getCompletedAt(),
                a.getCancelledAt(), version(a))).toList();
    }

    /** A queue row: the parcel with the task it needs next. */
    static QueueParcelResponse queueRow(DeliverySnapshot parcel, Instant waitingSince) {
        ParcelRules.Leg leg = ParcelRules.nextLeg(parcel);
        return new QueueParcelResponse(parcel.id(), parcel.trackingNumber(), parcel.status(), place(parcel.pickup()),
                place(parcel.recipient()), parcel.weightKg(), parcel.currentBranch(), waitingSince, leg.type(),
                leg.origin(), leg.destination());
    }

    static PlaceResponse place(DeliveryContact contact) {
        return contact == null ? null : new PlaceResponse(contact.city(), contact.district());
    }

    private static AssignmentItemResponse item(AssignmentItem item, DeliverySnapshot parcel) {
        return new AssignmentItemResponse(item.getParcelId(), item.getSequence(),
                parcel == null ? null : parcel.trackingNumber(),
                parcel == null ? null : parcel.status(),
                parcel == null ? null : place(parcel.pickup()),
                parcel == null ? null : place(parcel.recipient()),
                item.getOutcome(), item.getProblem(), item.getFailureReason(), item.getNote(), item.getReceivedBy(),
                item.getRecipientName(), item.getUpdatedAt(), item.getClosedAt());
    }

    private static long version(CourierAssignment assignment) {
        return assignment.getVersion() == null ? 0 : assignment.getVersion();
    }
}
