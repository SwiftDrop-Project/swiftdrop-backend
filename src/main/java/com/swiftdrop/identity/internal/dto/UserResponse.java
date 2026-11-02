package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.MerchantProfiles;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.reference.District;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** The "User" shape from API.md, returned by the auth endpoints (merchants has its own copy for the admin endpoints). */
public record UserResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        @Schema(nullable = true, description = "Only for role MERCHANT, otherwise null")
        MerchantInfo merchant) {

    /** {@code merchant} is null for everyone except merchants. */
    public static UserResponse of(UserSummary user, MerchantProfiles.Profile merchant) {
        MerchantInfo info = merchant == null ? null : new MerchantInfo(merchant.businessName(),
                merchant.pickupDistrict(), merchant.city(), merchant.approvalStatus());
        return new UserResponse(user.id(), user.fullName(), user.email(), user.phone(), user.role(), user.status(),
                info);
    }

    public record MerchantInfo(
            String businessName,
            District pickupDistrict,
            String city,
            @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED"})
            String approvalStatus) {
    }
}
