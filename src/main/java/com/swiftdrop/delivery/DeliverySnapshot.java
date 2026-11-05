package com.swiftdrop.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only copy of a parcel that other modules may use. Exactly one of {@code customerId} and {@code merchantId}
 * (identity user ids) is set; {@code courierId} is null until dispatch assigns a courier. {@code feeAmount} is the
 * price fixed at booking time. {@code version} goes up by one on every change, so a consumer can ignore an older
 * update that arrives late.
 *
 * <p>Contains personal data (names, phone numbers, addresses): never log it, and never show it on the public
 * tracking page, which may show only the status, city and district.
 */
public record DeliverySnapshot(
        UUID id,
        String trackingNumber,
        UUID customerId,
        UUID merchantId,
        String merchantOrderRef,
        UUID courierId,
        ParcelStatus status,
        ContactAddress pickup,
        ContactAddress dropoff,
        BigDecimal weightKg,
        String description,
        BigDecimal feeAmount,
        String currency,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
