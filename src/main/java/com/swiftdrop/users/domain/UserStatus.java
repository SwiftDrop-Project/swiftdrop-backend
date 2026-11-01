package com.swiftdrop.users.domain;

public enum UserStatus {
    /** Can log in. */
    ACTIVE,
    /** Merchant waiting for an admin to approve the application. */
    PENDING_APPROVAL,
    /** Merchant application rejected by an admin. */
    REJECTED,
    /** Blocked by an admin. */
    DISABLED
}
