package com.swiftdrop.delivery;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Input for creating a delivery: a customer's booking or a merchant's API order. Exactly one of {@code customerId}
 * and {@code merchantId} (identity user ids) is set. {@code merchantOrderRef} (the merchant's own order id),
 * {@code codAmount} (cash to collect on delivery in rupees; null when already paid) and {@code instructions}
 * (delivery instructions from the sender) are optional. Every field has the same name and type as in
 * {@link DeliverySnapshot}.
 *
 * <p>There is no fee here: the delivery module asks pricing for it, so a caller can never choose its own price.
 *
 * @param size the packed measurements, needed to book: {@link DeliveryApi#create} refuses a command without them
 *             (400 VALIDATION_FAILED on {@code lengthCm}), because without them pricing can't choose the vehicle
 */
public record CreateDeliveryCommand(
        UUID customerId,
        UUID merchantId,
        String merchantOrderRef,
        DeliveryContact pickup,
        DeliveryContact recipient,
        BigDecimal weightKg,
        BigDecimal codAmount,
        String instructions,
        ParcelSize size) {

    public CreateDeliveryCommand {
        if ((customerId == null) == (merchantId == null)) {
            throw new IllegalArgumentException("Exactly one of customerId and merchantId must be set");
        }
        Objects.requireNonNull(pickup, "pickup");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(weightKg, "weightKg");
    }

    /**
     * A command without measurements. Kept so code written before parcel sizes still compiles, but
     * {@link DeliveryApi#create} refuses it: pass a {@link ParcelSize} to book.
     */
    public CreateDeliveryCommand(UUID customerId, UUID merchantId, String merchantOrderRef, DeliveryContact pickup,
                                 DeliveryContact recipient, BigDecimal weightKg, BigDecimal codAmount,
                                 String instructions) {
        this(customerId, merchantId, merchantOrderRef, pickup, recipient, weightKg, codAmount, instructions, null);
    }

    /** Leaves out the instructions: free text that may hold personal data. The contacts show only city and district. */
    @Override
    public String toString() {
        return "CreateDeliveryCommand[customerId=" + customerId + ", merchantId=" + merchantId
                + ", merchantOrderRef=" + merchantOrderRef + ", pickup=" + pickup + ", recipient=" + recipient
                + ", weightKg=" + weightKg + ", codAmount=" + codAmount + ", size=" + size + "]";
    }
}
