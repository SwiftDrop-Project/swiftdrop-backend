package com.swiftdrop.customer;

import java.time.Instant;
import java.util.UUID;

/** Read-only copy of customer details and preferences that other modules may use. */
public record CustomerSummary(
        UUID userId,
        String fullName,
        String email,
        String phone,
        UUID defaultPickupAddressId,
        PaymentMethod preferredPaymentMethod,
        Instant customerSince
) {
}
