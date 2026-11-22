package com.swiftdrop.customer.internal.dto;

import com.swiftdrop.customer.PaymentMethod;

import java.time.Instant;
import java.util.UUID;

public record CustomerProfileResponse(
        UUID userId,
        String fullName,
        String email,
        String phone,
        Instant customerSince,
        long totalDeliveries,
        long savedAddressesCount,
        UUID defaultPickupAddressId,
        PaymentMethod preferredPaymentMethod
) {
}
