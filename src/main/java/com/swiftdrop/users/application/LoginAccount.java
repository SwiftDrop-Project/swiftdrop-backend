package com.swiftdrop.users.application;

import com.swiftdrop.users.domain.Role;
import com.swiftdrop.users.domain.UserStatus;

import java.util.UUID;

/** What the auth module needs to check a login. */
public record LoginAccount(UUID id, String passwordHash, Role role, UserStatus status) {

    /** Keeps the password hash out of logs. */
    @Override
    public String toString() {
        return "LoginAccount[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
}
