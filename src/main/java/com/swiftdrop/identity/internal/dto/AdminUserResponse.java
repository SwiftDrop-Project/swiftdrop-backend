package com.swiftdrop.identity.internal.dto;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.internal.domain.User;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of GET /admin/users. Never includes password hashes, tokens or Google identifiers.
 * {@code phone} is null only while a Google sign-up is not finished.
 */
public record AdminUserResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        Role role,
        UserStatus status,
        Instant createdAt) {

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.getStatus(), user.getCreatedAt());
    }

    /** Keeps personal contact details out of logs. */
    @Override
    public String toString() {
        return "AdminUserResponse[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
}
