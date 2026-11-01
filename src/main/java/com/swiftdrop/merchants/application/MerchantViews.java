package com.swiftdrop.merchants.application;

import com.swiftdrop.merchants.domain.ApprovalStatus;
import com.swiftdrop.merchants.domain.ParcelsPerMonth;
import com.swiftdrop.shared.meta.District;
import com.swiftdrop.users.application.UserView;

import java.time.Instant;
import java.util.UUID;

/** Read-only records the merchants module hands to other modules and to its own controllers. */
public final class MerchantViews {

    private MerchantViews() {
    }

    /** Input for a merchant sign-up. {@code phone} must already be normalised. */
    public record NewMerchant(String contactPerson, String email, String phone, String rawPassword,
                              String businessName, String businessRegNo, ParcelsPerMonth parcelsPerMonth,
                              District pickupDistrict, String city) {

        /** Keeps personal data and the password out of logs. */
        @Override
        public String toString() {
            return "NewMerchant[district=" + pickupDistrict + "]";
        }
    }

    public record MerchantProfileView(UUID userId, String businessName, String businessRegNo,
                                      ParcelsPerMonth parcelsPerMonth, District pickupDistrict, String city,
                                      ApprovalStatus approvalStatus, Instant reviewedAt, UUID reviewedBy) {
    }

    /** A merchant user together with its profile. */
    public record MerchantAccountView(UserView user, MerchantProfileView profile) {
    }

    /** One row of the admin "merchant applications" list. */
    public record MerchantApplicationView(UUID userId, String businessName, String contactPerson, String email,
                                          String phone, District pickupDistrict, String city,
                                          ParcelsPerMonth parcelsPerMonth, ApprovalStatus approvalStatus,
                                          Instant createdAt) {
    }
}
