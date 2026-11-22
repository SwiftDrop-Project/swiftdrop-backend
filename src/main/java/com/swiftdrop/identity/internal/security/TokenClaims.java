package com.swiftdrop.identity.internal.security;

/** Custom claims in the SD_TOKEN JWT (besides the standard sub, iat and exp). */
public final class TokenClaims {

    /** The user's role, e.g. ADMIN. Mapped to the authority ROLE_ADMIN. */
    public static final String ROLE = "role";

    /** The user's status when the token was issued, e.g. ACTIVE or PROFILE_INCOMPLETE. */
    public static final String STATUS = "status";

    /**
     * The user's token version when the token was issued. A token whose version differs from the database is
     * rejected (see SessionValidityFilter), which ends sessions after a password change or reset.
     */
    public static final String VERSION = "ver";

    /** True when the user must change an initial or admin-reset password before using protected endpoints. */
    public static final String MUST_CHANGE_PASSWORD = "pwd_change";

    private TokenClaims() {
    }
}
