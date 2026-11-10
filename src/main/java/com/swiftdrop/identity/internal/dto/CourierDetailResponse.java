package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.VehicleType;

import java.time.Instant;
import java.util.UUID;

/** Admin view of a courier account. Never includes password hashes or OAuth identifiers. */
public record CourierDetailResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        District branch,
        VehicleType vehicleType,
        UserStatus status,
        boolean mustChangePassword,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    public static CourierDetailResponse from(User user) {
        return new CourierDetailResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getBranch(),
                user.getVehicleType(),
                user.getStatus(),
                user.isMustChangePassword(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getDeletedAt());
    }

    /** Keeps personal contact details out of logs. */
    @Override
    public String toString() {
        return "CourierDetailResponse[id=" + id + ", branch=" + branch + ", vehicleType=" + vehicleType
                + ", status=" + status + "]";
    }
}

