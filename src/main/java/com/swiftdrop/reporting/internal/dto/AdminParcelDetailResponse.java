package com.swiftdrop.reporting.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /admin/parcels/{id}: one parcel in full, with its sender account and its status history (newest first).
 * Holds personal data (names, phones, addresses): admins only, never logged.
 */
public record AdminParcelDetailResponse(
        UUID id,
        String trackingNumber,
        ParcelStatus status,
        Sender sender,
        Contact pickup,
        Contact recipient,
        BigDecimal weightKg,
        BigDecimal codAmount,
        String instructions,
        BigDecimal feeAmount,
        String currency,
        String merchantOrderRef,
        District currentBranch,
        FailureReason failureReason,
        String proofRecipientName,
        boolean hasProofPhoto,
        Instant createdAt,
        Instant updatedAt,
        List<TrackingEntry> history) {

    public AdminParcelDetailResponse {
        history = List.copyOf(history);
    }

    /**
     * The account that booked the parcel.
     *
     * @param type         CUSTOMER or MERCHANT
     * @param accountName  the account holder's name; null if the account no longer exists
     * @param businessName merchants only, otherwise null
     */
    public record Sender(String type, UUID accountId, String accountName, String accountEmail, String businessName) {
    }

    /** A pickup or delivery contact. {@code phone} is +947XXXXXXXX. */
    public record Contact(String name, String phone, String addressLine, String city, District district) {

        static Contact from(DeliveryContact contact) {
            return new Contact(contact.name(), contact.phone(), contact.addressLine(), contact.city(),
                    contact.district());
        }
    }

    /**
     * One status change. {@code actorRole}: who made it (e.g. CUSTOMER, COURIER, ADMIN); {@code branch} only for
     * AT_BRANCH; {@code failureReason} only for FAILED.
     */
    public record TrackingEntry(ParcelStatus status, Instant occurredAt, String actorRole, District branch,
                                String note, FailureReason failureReason) {

        public static TrackingEntry from(ParcelStatusEntry entry) {
            return new TrackingEntry(entry.status(), entry.occurredAt(), entry.actorRole(), entry.branch(),
                    entry.note(), entry.failureReason());
        }
    }

    public static AdminParcelDetailResponse from(DeliverySnapshot parcel, Sender sender, List<TrackingEntry> history) {
        return new AdminParcelDetailResponse(parcel.id(), parcel.trackingNumber(), parcel.status(), sender,
                Contact.from(parcel.pickup()), Contact.from(parcel.recipient()), parcel.weightKg(), parcel.codAmount(),
                parcel.instructions(), parcel.feeAmount(), parcel.currency(), parcel.merchantOrderRef(),
                parcel.currentBranch(), parcel.failureReason(), parcel.proofRecipientName(), parcel.hasProofPhoto(),
                parcel.createdAt(), parcel.updatedAt(), history);
    }

    /** Personal data must not reach the logs. */
    @Override
    public String toString() {
        return "AdminParcelDetailResponse[id=" + id + ", status=" + status + "]";
    }
}
