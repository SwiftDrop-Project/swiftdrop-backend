package com.swiftdrop.shared.security;

/** Custom claims in the SD_TOKEN JWT (besides the standard sub, iat and exp). */
public final class TokenClaims {

    /** The user's role, e.g. ADMIN. Mapped to the authority ROLE_ADMIN. */
    public static final String ROLE = "role";

    /** The user's status when the token was issued, e.g. ACTIVE or PROFILE_INCOMPLETE. */
    public static final String STATUS = "status";

    private TokenClaims() {
    }
}
