package com.swiftdrop.courier.internal.dto;

import com.swiftdrop.courier.internal.application.CourierViews.MyItem;
import com.swiftdrop.courier.internal.application.CourierViews.ParcelDetail;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.reference.District;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code GET /courier/parcels/{parcelId}}: one parcel the courier handles or handled.
 *
 * @param currentBranch the branch the parcel is at (only for AT_BRANCH), or null
 * @param myItems       the courier's own items for this parcel, newest task first
 * @param contact       only while the courier has an open item: the sender (PICKUP) or recipient (DELIVERY); never
 *                      for TRANSFER
 * @param history       newest first, without notes
 */
public record CourierParcelResponse(
        UUID parcelId,
        String trackingNumber,
        ParcelStatus status,
        District currentBranch,
        PlaceResponse from,
        PlaceResponse to,
        List<MyItemResponse> myItems,
        ContactResponse contact,
        List<HistoryEntryResponse> history) {

    /** One of the courier's items for the parcel. */
    public record MyItemResponse(UUID assignmentId, AssignmentType type, ItemOutcome outcome) {
    }

    public static CourierParcelResponse from(ParcelDetail detail) {
        DeliverySnapshot parcel = detail.parcel();
        ContactResponse contact = detail.myItems().stream()
                .filter(MyItem::open)
                .findFirst()
                .map(item -> ContactResponse.from(AssignmentItemResponse.contactFor(item.type(), parcel)))
                .orElse(null);
        return new CourierParcelResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                parcel.currentBranch(), PlaceResponse.from(parcel.pickup()), PlaceResponse.from(parcel.recipient()),
                detail.myItems().stream()
                        .map(item -> new MyItemResponse(item.assignmentId(), item.type(), item.outcome()))
                        .toList(),
                contact,
                detail.history().stream().map(HistoryEntryResponse::from).toList());
    }
}
