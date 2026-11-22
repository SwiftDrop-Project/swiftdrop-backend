package com.swiftdrop.pricing.internal.dto;

import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;

/**
 * One row of GET /admin/pricing/districts: a district (its branch) and the centre that distances are measured from.
 *
 * @param priced    true when the district has a centre, so routes to and from it can be priced
 * @param latitude  null when the district has no centre
 * @param longitude null when the district has no centre
 */
public record DistrictCentreResponse(District district, String name, boolean priced, BigDecimal latitude,
                                     BigDecimal longitude) {
}
