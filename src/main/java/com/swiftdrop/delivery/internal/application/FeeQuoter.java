package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.internal.domain.ParcelPrice;

/**
 * The delivery fee for a new parcel. The fee always comes from here, never from the caller, so nobody can choose
 * their own price. {@link PricingFeeQuoter} asks pricing (WP4), the same quote the fee estimate shows.
 */
interface FeeQuoter {

    /**
     * @throws com.swiftdrop.shared.error.BusinessRuleException PARCEL_NEEDS_MANUAL_QUOTE when no vehicle takes the
     *                                                          parcel
     */
    ParcelPrice quote(CreateDeliveryCommand command);
}
