package com.swiftdrop.pricing.internal.dto;

import com.swiftdrop.shared.reference.District;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of GET /admin/pricing/routes?from=: the distance and linehaul from one branch to every district, with the
 * active rate card's numbers they come from. The same values a quote for that route uses.
 *
 * @param routes one per district, in district order; the {@code from} district itself is 0 km
 */
public record RouteTableResponse(
        District from,
        String fromName,
        int rateCardVersion,
        String currency,
        BigDecimal roadFactor,
        BigDecimal linehaulPerKm,
        List<Route> routes) {

    public RouteTableResponse {
        routes = List.copyOf(routes);
    }

    /**
     * One destination.
     *
     * @param distanceKm road distance between the branches, one decimal; null when a district has no centre
     * @param linehaul   distanceKm x linehaulPerKm, two decimals; null when a district has no centre
     */
    public record Route(District to, String toName, BigDecimal distanceKm, BigDecimal linehaul) {
    }
}
