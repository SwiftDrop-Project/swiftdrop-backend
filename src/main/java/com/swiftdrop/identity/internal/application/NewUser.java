package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;

/**
 * Input for creating a user. {@code phone} must already be normalised (+947XXXXXXXX);
 * {@code rawPassword} is hashed by {@link UserService}.
 */
public record NewUser(String fullName, String email, String phone, String rawPassword, Role role, UserStatus status) {

    /** Keeps personal data and the password out of logs. */
    @Override
    public String toString() {
        return "NewUser[role=" + role + ", status=" + status + "]";
    }
}
