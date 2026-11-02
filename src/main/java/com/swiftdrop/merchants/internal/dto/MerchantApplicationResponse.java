package com.swiftdrop.merchants.internal.dto;

import com.swiftdrop.merchants.internal.application.MerchantViews.MerchantApplicationView;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;

import java.time.Instant;
import java.util.UUID;

/** One row of GET /admin/merchants. {@code createdAt} is the sign-up time. */
public record MerchantApplicationResponse(UUID userId, String businessName, String contactPerson, String email,
                                          String phone, District pickupDistrict, String city,
                                          ParcelsPerMonth parcelsPerMonth, Instant createdAt) {

    public static MerchantApplicationResponse from(MerchantApplicationView v) {
        return new MerchantApplicationResponse(v.userId(), v.businessName(), v.contactPerson(), v.email(),
                v.phone(), v.pickupDistrict(), v.city(), v.parcelsPerMonth(), v.createdAt());
    }
}
