package com.swiftdrop.merchants.internal.dto;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.merchants.internal.application.MerchantViews.MerchantAccountView;
import com.swiftdrop.merchants.internal.application.MerchantViews.MerchantProfileView;
import com.swiftdrop.shared.reference.District;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * The "User" shape from API.md for a merchant, returned by approve and reject. Must stay field-for-field identical
 * to identity's UserResponse (the frontend uses one User type).
 */
public record MerchantAccountResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        MerchantInfo merchant) {

    public static MerchantAccountResponse from(MerchantAccountView account) {
        UserSummary user = account.user();
        MerchantProfileView p = account.profile();
        return new MerchantAccountResponse(user.id(), user.fullName(), user.email(), user.phone(), user.role(),
                user.status(), new MerchantInfo(p.businessName(), p.pickupDistrict(), p.city(),
                p.approvalStatus().name()));
    }

    @Schema(name = "MerchantAccountInfo")
    public record MerchantInfo(
            String businessName,
            District pickupDistrict,
            String city,
            @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED"})
            String approvalStatus) {
    }
}
