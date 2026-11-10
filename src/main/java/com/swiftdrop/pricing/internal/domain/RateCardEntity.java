package com.swiftdrop.pricing.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A row of pricing.rate_cards. Read-only ({@code @Immutable}): prices change through a new version, never by editing
 * one. {@link #toRateCard} turns it into the plain {@link RateCard} the calculator works with.
 */
@Entity
@Immutable
@Table(name = "rate_cards", schema = "pricing")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RateCardEntity {

    @Id
    private UUID id;

    /** The rate card version (not an optimistic-lock version). */
    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "base_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseFee;

    @Column(name = "base_weight_kg", nullable = false)
    private int baseWeightKg;

    @Column(name = "per_kg_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal perKgFee;

    @Column(name = "volumetric_divisor", nullable = false)
    private int volumetricDivisor;

    @Column(name = "cod_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal codFee;

    @Column(name = "road_factor", nullable = false, precision = 4, scale = 2)
    private BigDecimal roadFactor;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For tests: rate cards are added by migrations. */
    public RateCardEntity(UUID id, int version, String currency, BigDecimal baseFee, int baseWeightKg,
                          BigDecimal perKgFee, int volumetricDivisor, BigDecimal codFee, BigDecimal roadFactor,
                          boolean active, Instant createdAt) {
        this.id = id;
        this.version = version;
        this.currency = currency;
        this.baseFee = baseFee;
        this.baseWeightKg = baseWeightKg;
        this.perKgFee = perKgFee;
        this.volumetricDivisor = volumetricDivisor;
        this.codFee = codFee;
        this.roadFactor = roadFactor;
        this.active = active;
        this.createdAt = createdAt;
    }

    /** The rate card with its vehicles, for the calculator. */
    public RateCard toRateCard(List<VehicleRateEntity> vehicles) {
        return new RateCard(version, currency, baseFee, baseWeightKg, perKgFee, volumetricDivisor, codFee, roadFactor,
                vehicles.stream().map(VehicleRateEntity::toVehicleRate).toList());
    }
}
