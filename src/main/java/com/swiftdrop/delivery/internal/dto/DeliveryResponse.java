package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A delivery as its customer sees it: the response of POST /deliveries. */
public record DeliveryResponse(
        UUID id,
        String trackingNumber,
        ParcelStatus status,
        DeliveryContact pickup,
        DeliveryContact recipient,
        BigDecimal weightKg,
        BigDecimal codAmount,
        String instructions,
        BigDecimal feeAmount,
        String currency,
        Instant createdAt) {

    public static DeliveryResponse from(DeliverySnapshot parcel) {
        return new DeliveryResponse(parcel.id(), parcel.trackingNumber(), parcel.status(), parcel.pickup(),
                parcel.recipient(), parcel.weightKg(), parcel.codAmount(), parcel.instructions(),
                parcel.feeAmount(), parcel.currency(), parcel.createdAt());
    }

    /** The contacts and the instructions are personal data: only the tracking number and the status. */
    @Override
    public String toString() {
        return "DeliveryResponse[trackingNumber=" + trackingNumber + ", status=" + status + "]";
    }
}
