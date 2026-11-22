package com.swiftdrop.reporting.internal.dto;

import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of GET /admin/parcels. No phone numbers or street addresses: those are on the details page.
 *
 * @param senderType    CUSTOMER (booked on the website) or MERCHANT (an API or manual order)
 * @param senderName    the pickup contact's name
 * @param codAmount     cash to collect on delivery in rupees; null when already paid
 * @param currentBranch the branch the parcel is waiting at; only while AT_BRANCH
 */
public record AdminParcelResponse(
        UUID id,
        String trackingNumber,
        ParcelStatus status,
        String senderType,
        String senderName,
        String receiverName,
        String pickupCity,
        District pickupDistrict,
        String deliveryCity,
        District deliveryDistrict,
        District currentBranch,
        BigDecimal weightKg,
        BigDecimal feeAmount,
        String currency,
        BigDecimal codAmount,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminParcelResponse from(DeliverySnapshot parcel) {
        return new AdminParcelResponse(parcel.id(), parcel.trackingNumber(), parcel.status(),
                parcel.merchantId() != null ? "MERCHANT" : "CUSTOMER", parcel.pickup().name(),
                parcel.recipient().name(), parcel.pickup().city(), parcel.pickup().district(),
                parcel.recipient().city(), parcel.recipient().district(), parcel.currentBranch(), parcel.weightKg(),
                parcel.feeAmount(), parcel.currency(), parcel.codAmount(), parcel.createdAt(), parcel.updatedAt());
    }

    /** Names must not reach the logs. */
    @Override
    public String toString() {
        return "AdminParcelResponse[id=" + id + ", status=" + status + "]";
    }
}
