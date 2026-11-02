package com.swiftdrop.merchants.internal.application;

import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;

import java.time.Instant;
import java.util.UUID;

/** Read-only records the merchants module hands to its own controllers. */
public final class MerchantViews {

    private MerchantViews() {
    }

    public record MerchantProfileView(UUID userId, String businessName, String businessRegNo,
                                      ParcelsPerMonth parcelsPerMonth, District pickupDistrict, String city,
                                      ApprovalStatus approvalStatus, Instant reviewedAt, UUID reviewedBy) {
    }

    /** A merchant user together with its profile. */
    public record MerchantAccountView(UserSummary user, MerchantProfileView profile) {
    }

    /** One row of the admin "merchant applications" list. */
    public record MerchantApplicationView(UUID userId, String businessName, String contactPerson, String email,
                                          String phone, District pickupDistrict, String city,
                                          ParcelsPerMonth parcelsPerMonth, ApprovalStatus approvalStatus,
                                          Instant createdAt) {
    }
}
