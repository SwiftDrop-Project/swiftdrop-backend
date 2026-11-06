package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * TEMPORARY: the same fee for every parcel, until pricing (WP4) adds a quote method to PricingApi. Then a
 * FeeQuoter that calls PricingApi replaces this class.
 */
@Component
class FlatFeeQuoter implements FeeQuoter {

    static final Fee FLAT_FEE = new Fee(new BigDecimal("450.00"), "LKR");

    @Override
    public Fee quote(CreateDeliveryCommand command) {
        return FLAT_FEE;
    }
}
