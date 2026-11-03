package com.swiftdrop.identity.internal.domain;

/** What a one-time email code is for. A new code invalidates the older codes of the same purpose. */
public enum VerificationPurpose {
    /** Confirms the email address after a password sign-up. */
    EMAIL_VERIFICATION,
    /** Lets the owner of the email set a new password. */
    PASSWORD_RESET
}
