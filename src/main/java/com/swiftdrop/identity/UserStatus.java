package com.swiftdrop.identity;

public enum UserStatus {
    /** Can log in. */
    ACTIVE,
    /**
     * Signed up with a password and has not entered the 6-digit code from the verification email yet.
     * Cannot log in (403 EMAIL_NOT_VERIFIED). After verification: customers become ACTIVE, merchants PENDING_APPROVAL.
     */
    PENDING_EMAIL_VERIFICATION,
    /** Merchant waiting for an admin to approve the application. */
    PENDING_APPROVAL,
    /** Merchant application rejected by an admin. */
    REJECTED,
    /** Blocked by an admin. */
    DISABLED,
    /** Signed up with Google and must still call POST /auth/complete-profile (no phone yet). */
    PROFILE_INCOMPLETE
}
