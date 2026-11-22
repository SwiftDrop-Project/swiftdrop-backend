package com.swiftdrop.customer.internal.dto;

import com.swiftdrop.customer.PaymentMethod;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdatePreferencesRequest(
        UUID defaultPickupAddressId,

        @NotNull(message = "Preferred payment method is required")
        PaymentMethod preferredPaymentMethod
) {
}
