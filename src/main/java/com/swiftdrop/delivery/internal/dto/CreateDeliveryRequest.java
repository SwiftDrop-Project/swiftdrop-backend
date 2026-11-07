package com.swiftdrop.delivery.internal.dto;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of POST /deliveries: a customer's booking. There is no customer id (it comes from the login) and no fee (the
 * server sets it), so nobody can book for someone else or choose their own price.
 */
public record CreateDeliveryRequest(
        @NotNull(message = "Enter the pickup details.") @Valid ContactRequest pickup,
        @NotNull(message = "Enter the recipient's details.") @Valid ContactRequest recipient,
        @Schema(example = "2.5")
        @NotNull(message = "Enter the weight.")
        @DecimalMin(value = "0.01", message = "Enter a weight above 0 kg.")
        @DecimalMax(value = "50.00", message = "A parcel can weigh up to 50 kg.")
        @Digits(integer = 2, fraction = 2, message = "Use up to 2 decimal places.") BigDecimal weightKg,
        @Schema(example = "2500.00", nullable = true, description = "Cash to collect on delivery; leave out when paid")
        @DecimalMin(value = "0.01", message = "Enter an amount above 0, or leave it out.")
        @DecimalMax(value = "500000.00", message = "Cash on delivery can be up to Rs. 500,000.")
        @Digits(integer = 6, fraction = 2, message = "Use up to 2 decimal places.") BigDecimal codAmount,
        @Schema(example = "Call before coming", nullable = true)
        @Size(max = 500, message = "Use 500 characters or fewer.") String instructions) {

    /** The command for DeliveryApi.create: the customer from the login, the phones in their stored form. */
    public CreateDeliveryCommand toCommand(UUID customerId) {
        String cleanInstructions = instructions == null || instructions.isBlank() ? null : instructions.strip();
        return new CreateDeliveryCommand(customerId, null, null, pickup.toContact(), recipient.toContact(), weightKg,
                codAmount, cleanInstructions);
    }

    /** The contacts and the instructions are personal data: only the weight. */
    @Override
    public String toString() {
        return "CreateDeliveryRequest[weightKg=" + weightKg + "]";
    }
}
