package com.swiftdrop.pricing.internal.dto;

import com.swiftdrop.pricing.QuoteRequest;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.reference.District;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Body of POST /pricing/estimate. The weight rules are the booking's (POST /deliveries), so an estimate is only given
 * for a parcel that can be booked. The three measurements come together or not at all.
 */
public record FeeEstimateRequest(
        @Schema(example = "COLOMBO")
        @NotNull(message = "Select the pickup district.") District pickupDistrict,
        @Schema(example = "KANDY")
        @NotNull(message = "Select the drop-off district.") District dropOffDistrict,
        @Schema(example = "1.8")
        @NotNull(message = "Enter the weight.")
        @DecimalMin(value = "0.01", message = "Enter a weight above 0 kg.")
        @DecimalMax(value = "50.00", message = "A parcel can weigh up to 50 kg.")
        @Digits(integer = 2, fraction = 2, message = "Use up to 2 decimal places.") BigDecimal weightKg,
        @Schema(example = "40", nullable = true)
        @Min(value = 1, message = MEASUREMENT) @Max(value = 300, message = MEASUREMENT) Integer lengthCm,
        @Schema(example = "30", nullable = true)
        @Min(value = 1, message = MEASUREMENT) @Max(value = 300, message = MEASUREMENT) Integer widthCm,
        @Schema(example = "12", nullable = true)
        @Min(value = 1, message = MEASUREMENT) @Max(value = 300, message = MEASUREMENT) Integer heightCm,
        boolean keepUpright,
        boolean cashOnDelivery) {

    static final String MEASUREMENT = "Use whole centimetres from 1 to 300.";
    static final String ALL_OR_NONE = "Enter all three measurements, or leave them all empty.";

    /**
     * The quote request. Some measurements without the others are a 400 on the first empty one.
     *
     * @throws FieldValidationException when only some of the measurements are given
     */
    public QuoteRequest toQuoteRequest() {
        Map<String, Integer> measurements = new LinkedHashMap<>();
        measurements.put("lengthCm", lengthCm);
        measurements.put("widthCm", widthCm);
        measurements.put("heightCm", heightCm);
        boolean anyGiven = measurements.values().stream().anyMatch(value -> value != null);
        if (anyGiven) {
            measurements.entrySet().stream()
                    .filter(entry -> entry.getValue() == null)
                    .findFirst()
                    .ifPresent(missing -> {
                        throw new FieldValidationException(missing.getKey(), ALL_OR_NONE);
                    });
        }
        return new QuoteRequest(pickupDistrict, dropOffDistrict, weightKg, lengthCm, widthCm, heightCm, keepUpright,
                cashOnDelivery);
    }
}
