package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;

import java.util.UUID;

/** What the auth module needs to check a login. {@code passwordHash} is null for Google-only accounts. */
public record LoginAccount(UUID id, String passwordHash, Role role, UserStatus status) {

    /** Keeps the password hash out of logs. */
    @Override
    public String toString() {
        return "LoginAccount[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
}
