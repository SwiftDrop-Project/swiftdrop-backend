package com.swiftdrop.users.api;

import com.swiftdrop.shared.meta.District;
import com.swiftdrop.users.application.UserView;
import com.swiftdrop.users.domain.Role;
import com.swiftdrop.users.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** The "User" shape from API.md, returned by the auth and admin endpoints. */
public record UserResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        @Schema(nullable = true, description = "Only for role MERCHANT, otherwise null")
        MerchantInfo merchant) {

    public static UserResponse of(UserView user, MerchantInfo merchant) {
        return new UserResponse(user.id(), user.fullName(), user.email(), user.phone(), user.role(), user.status(),
                merchant);
    }

    public record MerchantInfo(
            String businessName,
            District pickupDistrict,
            String city,
            @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED"})
            String approvalStatus) {
    }
}
