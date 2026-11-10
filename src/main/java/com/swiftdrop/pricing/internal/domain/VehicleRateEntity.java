package com.swiftdrop.pricing.internal.domain;

import com.swiftdrop.pricing.Vehicle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of pricing.vehicle_rates: one vehicle of a rate card. Read-only, like the rate card. The table's
 * included_km and per_km_fee are not mapped: local legs have no distance charge until addresses have coordinates.
 */
@Entity
@Immutable
@Table(name = "vehicle_rates", schema = "pricing")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VehicleRateEntity {

    @Id
    private UUID id;

    /** pricing.rate_cards.id, a plain UUID (no foreign keys). */
    @Column(name = "rate_card_id", nullable = false)
    private UUID rateCardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Vehicle vehicle;

    @Column(name = "check_order", nullable = false)
    private int checkOrder;

    @Column(name = "max_length_cm", nullable = false)
    private int maxLengthCm;

    @Column(name = "max_width_cm", nullable = false)
    private int maxWidthCm;

    @Column(name = "max_height_cm", nullable = false)
    private int maxHeightCm;

    @Column(name = "max_weight_kg", nullable = false, precision = 7, scale = 2)
    private BigDecimal maxWeightKg;

    /** The price of one local leg (pickup or delivery); the column kept its first name. */
    @Column(name = "surcharge", nullable = false, precision = 10, scale = 2)
    private BigDecimal legFee;

    /** For tests: vehicle rates are added by migrations. */
    public VehicleRateEntity(UUID id, UUID rateCardId, Vehicle vehicle, int checkOrder, int maxLengthCm,
                             int maxWidthCm, int maxHeightCm, BigDecimal maxWeightKg, BigDecimal legFee) {
        this.id = id;
        this.rateCardId = rateCardId;
        this.vehicle = vehicle;
        this.checkOrder = checkOrder;
        this.maxLengthCm = maxLengthCm;
        this.maxWidthCm = maxWidthCm;
        this.maxHeightCm = maxHeightCm;
        this.maxWeightKg = maxWeightKg;
        this.legFee = legFee;
    }

    public VehicleRate toVehicleRate() {
        return new VehicleRate(vehicle, checkOrder, maxLengthCm, maxWidthCm, maxHeightCm, maxWeightKg, legFee);
    }
}
