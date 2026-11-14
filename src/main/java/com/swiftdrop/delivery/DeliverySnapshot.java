package com.swiftdrop.delivery;

import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only copy of a parcel that other modules may use.
 *
 * <p>{@code customerId}, {@code merchantId} and {@code assignedCourierId} are identity user ids.
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
 * @param assignedCourierId  deprecated (single-courier model; dispatch assignments replace it): the courier set by
 *                           {@link DeliveryApi#assignCourier}, or null
 * @param assignedAt         deprecated, like {@code assignedCourierId}: when that courier was set, or null
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
 * @param currentBranch      the branch the parcel is at (only for AT_BRANCH), or null. Not personal data: the public
 *                           tracking page may show it
 * @param size               the packed measurements it was priced with, or null (parcels booked before sizes)
 * @param vehicle            the vehicle of the local legs it was priced with (a pricing Vehicle name, e.g.
 *                           {@code BIKE}), or null
 * @param billableWeightKg   the billable weight it was priced with, or null
 * @param rateCardVersion    the pricing rate card of its fee, so the fee can be worked out again; null for the old
 *                           flat fee
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
        @Deprecated UUID assignedCourierId,
        @Deprecated Instant assignedAt,
        Instant createdAt,
        Instant updatedAt,
        String proofRecipientName,
        boolean hasProofPhoto,
        FailureReason failureReason,
        String merchantOrderRef,
        BigDecimal feeAmount,
        String currency,
        long version,
        District currentBranch,
        ParcelSize size,
        String vehicle,
        Integer billableWeightKg,
        Integer rateCardVersion) {

    /** A snapshot without pricing details. Kept so callers written before parcel sizes still compile. */
    public DeliverySnapshot(UUID id, String trackingNumber, ParcelStatus status, UUID customerId, UUID merchantId,
                            DeliveryContact pickup, DeliveryContact recipient, BigDecimal weightKg,
                            BigDecimal codAmount, String instructions, UUID assignedCourierId, Instant assignedAt,
                            Instant createdAt, Instant updatedAt, String proofRecipientName, boolean hasProofPhoto,
                            FailureReason failureReason, String merchantOrderRef, BigDecimal feeAmount,
                            String currency, long version, District currentBranch) {
        this(id, trackingNumber, status, customerId, merchantId, pickup, recipient, weightKg, codAmount, instructions,
                assignedCourierId, assignedAt, createdAt, updatedAt, proofRecipientName, hasProofPhoto, failureReason,
                merchantOrderRef, feeAmount, currency, version, currentBranch, null, null, null, null);
    }

    /**
     * A snapshot without a branch (any status except AT_BRANCH). Kept so callers written before branches still
     * compile.
     */
    public DeliverySnapshot(UUID id, String trackingNumber, ParcelStatus status, UUID customerId, UUID merchantId,
                            DeliveryContact pickup, DeliveryContact recipient, BigDecimal weightKg,
                            BigDecimal codAmount, String instructions, UUID assignedCourierId, Instant assignedAt,
                            Instant createdAt, Instant updatedAt, String proofRecipientName, boolean hasProofPhoto,
                            FailureReason failureReason, String merchantOrderRef, BigDecimal feeAmount,
                            String currency, long version) {
        this(id, trackingNumber, status, customerId, merchantId, pickup, recipient, weightKg, codAmount, instructions,
                assignedCourierId, assignedAt, createdAt, updatedAt, proofRecipientName, hasProofPhoto, failureReason,
                merchantOrderRef, feeAmount, currency, version, null);
    }

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
                + ", version=" + version + ", currentBranch=" + currentBranch + ", size=" + size
                + ", vehicle=" + vehicle + ", billableWeightKg=" + billableWeightKg
                + ", rateCardVersion=" + rateCardVersion + "]";
    }
}
