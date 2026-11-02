package com.swiftdrop.merchants;

import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;

import java.util.UUID;

/** Read-only copy of a merchant's business profile that other modules may use. {@code userId} is the identity user id. */
public record MerchantSummary(UUID userId, String businessName, District pickupDistrict, String city,
                              ParcelsPerMonth parcelsPerMonth, ApprovalStatus approvalStatus) {
}
