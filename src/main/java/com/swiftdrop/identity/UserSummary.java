package com.swiftdrop.identity;

import java.time.Instant;
import java.util.UUID;

/** Read-only copy of a user that other modules may use. Never contains the password hash. */
public record UserSummary(UUID id, String fullName, String email, String phone, Role role, UserStatus status,
                          boolean mustChangePassword, Instant createdAt) {

    public UserSummary(UUID id, String fullName, String email, String phone, Role role, UserStatus status,
                       Instant createdAt) {
        this(id, fullName, email, phone, role, status, false, createdAt);
    }

    /** Keeps personal contact details out of logs. */
    @Override
    public String toString() {
        return "UserSummary[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
}
