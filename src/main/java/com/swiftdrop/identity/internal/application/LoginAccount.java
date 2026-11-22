package com.swiftdrop.identity.internal.application;

import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;

import java.util.UUID;

/**
 * What the auth flows need to check a login and issue a token. {@code passwordHash} is null for Google-only
 * accounts; {@code tokenVersion} goes into the token's "ver" claim.
 */
public record LoginAccount(UUID id, String passwordHash, Role role, UserStatus status, int tokenVersion,
                           boolean mustChangePassword) {

    public LoginAccount(UUID id, String passwordHash, Role role, UserStatus status, int tokenVersion) {
        this(id, passwordHash, role, status, tokenVersion, false);
    }

    /** Keeps the password hash out of logs. */
    @Override
    public String toString() {
        return "LoginAccount[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
}
