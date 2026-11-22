package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.domain.User;
import com.swiftdrop.shared.reference.District;

import java.time.Instant;
import java.util.UUID;

/** Admin view of a dispatcher account. Never includes password hashes or OAuth identifiers. */
public record DispatcherDetailResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        District branch,
        UserStatus status,
        boolean mustChangePassword,
        Instant createdAt,
        Instant updatedAt) {

    public static DispatcherDetailResponse from(User user) {
        return new DispatcherDetailResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getBranch(),
                user.getStatus(),
                user.isMustChangePassword(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    /** Keeps personal contact details out of logs. */
    @Override
    public String toString() {
        return "DispatcherDetailResponse[id=" + id + ", branch=" + branch + ", status=" + status + "]";
    }
}

