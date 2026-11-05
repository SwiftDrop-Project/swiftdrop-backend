package com.swiftdrop.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only copy of a parcel that other modules may use.
 *
 * <p>The assigned courier is stored on the parcel by delivery (dispatch owns no schema; it only asks delivery to
 * assign one). {@code customerId}, {@code merchantId} and {@code assignedCourierId} are identity user ids.
 *
 * <p>Contains personal data (the contacts, the proof recipient's name and the free-text instructions): never log it,
 * and never show it on the public tracking page, which may show only the status, city and district.
 * {@link #toString()} leaves the personal data out.
 *
 * <p>Contract owned by delivery; agreed with WP9 (courier). WP3 may add fields: append them at the end so
 * existing callers keep compiling with few changes.
 *
 * @param id                 parcel id
 * @param trackingNumber     normalised, e.g. {@code SD2611004817} (no spaces, upper case)
 * @param status             current status
 * @param customerId         the customer who booked it, or null for a merchant order
 * @param merchantId         the merchant who created it, or null for a customer booking
 * @param pickup             where the courier collects the parcel
 * @param recipient          who receives it and where
 * @param weightKg           parcel weight in kilograms
 * @param codAmount          cash to collect on delivery in rupees; null when already paid
 * @param instructions       delivery instructions from the sender, or null
 * @param assignedCourierId  the courier currently responsible, or null while unassigned
 * @param assignedAt         when that courier was assigned, or null while unassigned
 * @param createdAt          when the parcel was created
 * @param updatedAt          when the parcel last changed (status, assignment, proof)
 * @param proofRecipientName who received the parcel (only for DELIVERED), or null
 * @param hasProofPhoto      whether a proof-of-delivery photo is attached (only for DELIVERED)
 * @param failureReason      why delivery failed (only for FAILED), or null
 * @param merchantOrderRef   the merchant's own order id, or null
 * @param feeAmount          the delivery fee, fixed when the parcel was booked
 * @param currency           the fee's currency, e.g. {@code LKR}
 * @param version            goes up by one on every change, so a consumer can ignore an older update that arrives
 *                           late
 */
public record DeliverySnapshot(
        UUID id,
        String trackingNumber,
        ParcelStatus status,
        UUID customerId,
        UUID merchantId,
        DeliveryContact pickup,
        DeliveryContact recipient,
        BigDecimal weightKg,
        BigDecimal codAmount,
        String instructions,
        UUID assignedCourierId,
        Instant assignedAt,
        Instant createdAt,
        Instant updatedAt,
        String proofRecipientName,
        boolean hasProofPhoto,
        FailureReason failureReason,
        String merchantOrderRef,
        BigDecimal feeAmount,
        String currency,
        long version) {

    /**
     * Every field except the proof recipient's name and the instructions; the contacts show only city and district.
     * A field added later is left out until it is added here, so new personal data is hidden by default.
     */
    @Override
    public String toString() {
        return "DeliverySnapshot[id=" + id + ", trackingNumber=" + trackingNumber + ", status=" + status
                + ", customerId=" + customerId + ", merchantId=" + merchantId
                + ", pickup=" + pickup + ", recipient=" + recipient
                + ", weightKg=" + weightKg + ", codAmount=" + codAmount
                + ", assignedCourierId=" + assignedCourierId + ", assignedAt=" + assignedAt
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", hasProofPhoto=" + hasProofPhoto + ", failureReason=" + failureReason
                + ", merchantOrderRef=" + merchantOrderRef + ", feeAmount=" + feeAmount + ", currency=" + currency
                + ", version=" + version + "]";
    }
}
