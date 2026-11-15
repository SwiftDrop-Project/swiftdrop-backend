package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatusEntry;

import java.util.List;

/**
 * GET /deliveries/{id}: one of the customer's deliveries and its status history, newest first. The delivery has the
 * same shape as everywhere else, so the frontend uses one type for it.
 */
public record DeliveryDetailResponse(DeliveryResponse delivery, List<DeliveryHistoryEntryResponse> history) {

    public static DeliveryDetailResponse from(DeliverySnapshot parcel, List<ParcelStatusEntry> history) {
        return new DeliveryDetailResponse(DeliveryResponse.from(parcel),
                history.stream().map(DeliveryHistoryEntryResponse::from).toList());
    }
}
