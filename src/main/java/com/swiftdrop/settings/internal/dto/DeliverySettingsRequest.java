
package com.swiftdrop.settings.internal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

public record DeliverySettingsRequest(
    @NotNull LocalTime pickupStart,
    @NotNull LocalTime pickupEnd,
    @NotNull LocalTime sameDayCutoff,
    @NotNull List<String> operatingDays,

    @DecimalMin("0.01") BigDecimal maxWeightKg,
    @DecimalMin("0.01") BigDecimal maxLengthCm,
    @DecimalMin("0.01") BigDecimal maxWidthCm,
    @DecimalMin("0.01") BigDecimal maxHeightCm,

    @Min(1) @Max(5) Integer failedAttempts
) {}
