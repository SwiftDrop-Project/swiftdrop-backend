package com.swiftdrop.delivery;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Input for creating a delivery: a customer's booking or a merchant's API order. Exactly one of {@code customerId}
 * and {@code merchantId} (identity user ids) is set. {@code merchantOrderRef} (the merchant's own order id) and
 * {@code description} (what is inside the parcel) are optional.
 *
 * <p>There is no fee here: the delivery module asks pricing for it, so a caller can never choose its own price.
 */
public record CreateDeliveryCommand(
        UUID customerId,
        UUID merchantId,
        String merchantOrderRef,
        ContactAddress pickup,
        ContactAddress dropoff,
        BigDecimal weightKg,
        String description) {

    public CreateDeliveryCommand {
        if ((customerId == null) == (merchantId == null)) {
            throw new IllegalArgumentException("Exactly one of customerId and merchantId must be set");
        }
        Objects.requireNonNull(pickup, "pickup");
        Objects.requireNonNull(dropoff, "dropoff");
        Objects.requireNonNull(weightKg, "weightKg");
    }
}
