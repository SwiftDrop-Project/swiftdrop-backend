package com.swiftdrop.users.application;

import com.swiftdrop.users.domain.Role;
import com.swiftdrop.users.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

/** Read-only copy of a user that other modules may use. Never contains the password hash. */
public record UserView(UUID id, String fullName, String email, String phone, Role role, UserStatus status,
                       Instant createdAt) {
}
