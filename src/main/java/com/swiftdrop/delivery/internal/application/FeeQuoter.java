package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.CreateDeliveryCommand;

import java.math.BigDecimal;

/**
 * The delivery fee for a new parcel. The fee always comes from here, never from the caller, so nobody can choose
 * their own price. Pricing (WP4) will give the real fee through PricingApi; until then {@link FlatFeeQuoter}.
 */
interface FeeQuoter {

    Fee quote(CreateDeliveryCommand command);

    /** An amount and its currency, e.g. 450.00 LKR. */
    record Fee(BigDecimal amount, String currency) {
    }
}
