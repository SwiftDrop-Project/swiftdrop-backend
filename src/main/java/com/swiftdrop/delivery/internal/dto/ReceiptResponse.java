package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The receipt of one of the customer's deliveries: GET /deliveries/{id}/receipt, and the content of the PDF at
 * GET /deliveries/{id}/receipt/pdf. It shows the fee as it was fixed at booking, line by line.
 *
 * @param trackingNumber   normalised, e.g. {@code SD2611003152}
 * @param bookedAt         when the parcel was booked
 * @param weightKg         the weight the customer entered
 * @param billableWeightKg the weight the fee was charged for (whole kg), or null for parcels booked before sizes
 * @param size             the packed measurements, or null for parcels booked before sizes
 * @param vehicle          the pickup and drop-off vehicle (a pricing Vehicle name, e.g. {@code BIKE}), or null
 * @param lines            the fee lines in the order to show them; one "Delivery fee" line for the old flat fee
 * @param total            the fee charged; the lines add up to it
 * @param codAmount        cash the courier collects from the recipient for the sender, or null. Not part of the fee
 * @param paymentMethod    how the fee is paid, or null for parcels booked before payment methods
 * @param paymentStatus    whether the fee has been paid, or null when SwiftDrop can't tell (online payments are not
 *                         recorded yet, and parcels without a payment method)
 */
public record ReceiptResponse(
        String trackingNumber,
        ParcelStatus status,
        Instant bookedAt,
        DeliveryContact pickup,
        DeliveryContact recipient,
        BigDecimal weightKg,
        Integer billableWeightKg,
        ParcelSize size,
        String vehicle,
        List<Line> lines,
        BigDecimal total,
        String currency,
        BigDecimal codAmount,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus) {

    public ReceiptResponse {
        lines = List.copyOf(lines);
    }

    /**
     * One fee line.
     *
     * @param component the pricing component ({@code BASE}, {@code WEIGHT}, {@code PICKUP}, {@code DELIVERY},
     *                  {@code LINEHAUL}, {@code COD}), or {@code FEE} for the old flat fee
     * @param label     how the receipt names it, e.g. "Extra weight (1 kg)"
     */
    public record Line(String component, String label, BigDecimal amount) {
    }

    /** Whether the delivery fee has been paid. */
    public enum PaymentStatus {
        /** Collected: at pickup once the parcel is picked up, at drop-off once it is delivered. */
        PAID,
        /** Not collected yet. */
        DUE
    }

    /** The contacts are personal data: only the tracking number, the status and the total. */
    @Override
    public String toString() {
        return "ReceiptResponse[trackingNumber=" + trackingNumber + ", status=" + status + ", total=" + total + "]";
    }
}
