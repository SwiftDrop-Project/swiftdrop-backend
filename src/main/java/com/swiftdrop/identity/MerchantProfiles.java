package com.swiftdrop.identity;

import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.ParcelsPerMonth;

import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by the merchants module. identity owns merchant sign-up (POST /auth/register, POST
 * /auth/complete-profile) and shows the merchant block in the "User" response, but it must not depend on merchants
 * (merchants depends on identity for approval, and module cycles are not allowed). So identity calls this interface,
 * and merchants provides the bean.
 */
public interface MerchantProfiles {

    /**
     * Creates the PENDING business profile for a user who was just created or completed as a MERCHANT. Runs in the
     * caller's transaction, so the user and the profile are saved together or not at all.
     */
    Profile create(UUID userId, SignUp signUp);

    /** The merchant block of the user's "User" response, if the user has a profile. */
    Optional<Profile> find(UUID userId);

    /** The business part of a merchant sign-up. Already validated. */
    record SignUp(String businessName, String businessRegNo, ParcelsPerMonth parcelsPerMonth,
                  District pickupDistrict, String city) {
    }

    /** {@code approvalStatus}: PENDING, APPROVED or REJECTED. */
    record Profile(String businessName, District pickupDistrict, String city, String approvalStatus) {
    }
}
