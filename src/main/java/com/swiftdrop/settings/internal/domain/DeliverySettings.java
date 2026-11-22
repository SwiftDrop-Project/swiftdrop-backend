
package com.swiftdrop.settings.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalTime;

@Entity
@Table(name = "delivery_settings")
public class DeliverySettings {

    @Id
    private Long id;

    @Column(name = "pickup_start", nullable = false)
    private LocalTime pickupStart;

    @Column(name = "pickup_end", nullable = false)
    private LocalTime pickupEnd;

    @Column(name = "same_day_cutoff", nullable = false)
    private LocalTime sameDayCutoff;

    @Column(name = "operating_days", nullable = false)
    private String operatingDays;

    @Column(name = "max_weight_kg")
    private BigDecimal maxWeightKg;

    @Column(name = "max_length_cm")
    private BigDecimal maxLengthCm;

    @Column(name = "max_width_cm")
    private BigDecimal maxWidthCm;

    @Column(name = "max_height_cm")
    private BigDecimal maxHeightCm;

    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts;

    protected DeliverySettings() {
    }

    public DeliverySettings(
            Long id,
            LocalTime pickupStart,
            LocalTime pickupEnd,
            LocalTime sameDayCutoff,
            String operatingDays,
            BigDecimal maxWeightKg,
            BigDecimal maxLengthCm,
            BigDecimal maxWidthCm,
            BigDecimal maxHeightCm,
            Integer failedAttempts) {

        this.id = id;
        this.pickupStart = pickupStart;
        this.pickupEnd = pickupEnd;
        this.sameDayCutoff = sameDayCutoff;
        this.operatingDays = operatingDays;
        this.maxWeightKg = maxWeightKg;
        this.maxLengthCm = maxLengthCm;
        this.maxWidthCm = maxWidthCm;
        this.maxHeightCm = maxHeightCm;
        this.failedAttempts = failedAttempts;
    }

    public Long getId() {
        return id;
    }

    public LocalTime getPickupStart() {
        return pickupStart;
    }

    public LocalTime getPickupEnd() {
        return pickupEnd;
    }

    public LocalTime getSameDayCutoff() {
        return sameDayCutoff;
    }

    public String getOperatingDays() {
        return operatingDays;
    }

    public BigDecimal getMaxWeightKg() {
        return maxWeightKg;
    }

    public BigDecimal getMaxLengthCm() {
        return maxLengthCm;
    }

    public BigDecimal getMaxWidthCm() {
        return maxWidthCm;
    }

    public BigDecimal getMaxHeightCm() {
        return maxHeightCm;
    }

    public Integer getFailedAttempts() {
        return failedAttempts;
    }
}
