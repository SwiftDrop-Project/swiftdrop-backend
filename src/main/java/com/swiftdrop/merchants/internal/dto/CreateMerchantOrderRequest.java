package com.swiftdrop.merchants.internal.dto;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of POST /merchant/orders. The merchant id comes from the login (cookie or API key), never from the request,
 * and the server sets the fee with pricing. Measurements are required: pricing needs them to pick the vehicle.
 * {@code merchantOrderRef} is the merchant's own order id; resending the same ref returns the existing parcel
 * (idempotent retry). The payment method is required: the courier needs it to know whether to collect the delivery
 * fee, and from whom.
 */
public record CreateMerchantOrderRequest(
        @Schema(example = "TECH-ORD-1001", nullable = true,
                description = "Merchant's own order id; resending the same value returns the existing parcel.")
        @Size(max = 100, message = "Use 100 characters or fewer.") String merchantOrderRef,
        @NotNull(message = "Enter the pickup details.") @Valid MerchantContactRequest pickup,
        @NotNull(message = "Enter the recipient's details.") @Valid MerchantContactRequest recipient,
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
        @Size(max = 500, message = "Use 500 characters or fewer.") String instructions,
        @Schema(example = "40", description = "Packed outside length in whole cm")
        @NotNull(message = "Enter the length.")
        @Min(value = 1, message = MEASUREMENT) @Max(value = 300, message = MEASUREMENT) Integer lengthCm,
        @Schema(example = "30")
        @NotNull(message = "Enter the width.")
        @Min(value = 1, message = MEASUREMENT) @Max(value = 300, message = MEASUREMENT) Integer widthCm,
        @Schema(example = "12")
        @NotNull(message = "Enter the height.")
        @Min(value = 1, message = MEASUREMENT) @Max(value = 300, message = MEASUREMENT) Integer heightCm,
        @Schema(defaultValue = "false", nullable = true) Boolean keepUpright,
        @Schema(example = "AT_PICKUP", description = "How the delivery fee is paid")
        @NotNull(message = PAYMENT_METHOD_REQUIRED) PaymentMethod paymentMethod) {

    static final String MEASUREMENT = "Use whole centimetres from 1 to 300.";
    static final String PAYMENT_METHOD_REQUIRED = "Choose how the delivery fee will be paid.";

    public CreateDeliveryCommand toCommand(UUID merchantId) {
        String ref = merchantOrderRef == null || merchantOrderRef.isBlank() ? null : merchantOrderRef.strip();
        String cleanInstructions = instructions == null || instructions.isBlank() ? null : instructions.strip();
        return new CreateDeliveryCommand(null, merchantId, ref, pickup.toContact(), recipient.toContact(), weightKg,
                codAmount, cleanInstructions,
                new ParcelSize(lengthCm, widthCm, heightCm, Boolean.TRUE.equals(keepUpright)), paymentMethod);
    }

    @Override
    public String toString() {
        return "CreateMerchantOrderRequest[merchantOrderRef=" + merchantOrderRef + ", weightKg=" + weightKg
                + ", lengthCm=" + lengthCm + ", widthCm=" + widthCm + ", heightCm=" + heightCm
                + ", keepUpright=" + keepUpright + ", paymentMethod=" + paymentMethod + "]";
    }
}
