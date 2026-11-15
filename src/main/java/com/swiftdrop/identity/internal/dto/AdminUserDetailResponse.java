package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.MerchantProfiles;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.VehicleType;

import java.time.Instant;
import java.util.UUID;

/**
 * GET /admin/users/{id}: one account in full. Never includes password hashes, tokens or the Google account id
 * (only whether Google sign-in is linked).
 *
 * @param branch      couriers and dispatchers only, otherwise null
 * @param vehicleType couriers only, otherwise null
 * @param merchant    merchants with a business profile only, otherwise null
 */
public record AdminUserDetailResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        boolean mustChangePassword,
        boolean googleLinked,
        District branch,
        VehicleType vehicleType,
        Merchant merchant,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    /** The merchant's business, from the merchants module. {@code approvalStatus}: PENDING, APPROVED or REJECTED. */
    public record Merchant(String businessName, District pickupDistrict, String city, String approvalStatus) {
    }

    public static AdminUserDetailResponse from(User user, MerchantProfiles.Profile profile) {
        Merchant merchant = profile == null ? null
                : new Merchant(profile.businessName(), profile.pickupDistrict(), profile.city(),
                        profile.approvalStatus());
        return new AdminUserDetailResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.getStatus(), user.isMustChangePassword(), user.getGoogleSub() != null,
                user.getBranch(), user.getVehicleType(), merchant, user.getCreatedAt(), user.getUpdatedAt(),
                user.getDeletedAt());
    }

    /** Keeps personal contact details out of logs. */
    @Override
    public String toString() {
        return "AdminUserDetailResponse[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
}
