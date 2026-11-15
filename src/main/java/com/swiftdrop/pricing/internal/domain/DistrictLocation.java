package com.swiftdrop.pricing.internal.domain;

import com.swiftdrop.shared.reference.District;
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

/** A row of pricing.district_locations: the centre (district capital) of a district, for distances. */
@Entity
@Immutable
@Table(name = "district_locations", schema = "pricing")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DistrictLocation {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private District district;

    @Column(nullable = false, precision = 8, scale = 5)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 8, scale = 5)
    private BigDecimal longitude;

    /** For tests: the centres are added by migrations. */
    public DistrictLocation(District district, BigDecimal latitude, BigDecimal longitude) {
        this.district = district;
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
