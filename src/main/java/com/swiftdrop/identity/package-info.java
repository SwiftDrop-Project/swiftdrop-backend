/**
 * Identity: user accounts and authentication.
 *
 * <p>Register, email verification (6-digit code), log in, log out, current user ({@code /auth/me}), forgot / reset /
 * change password, "Continue with Google" (OIDC), completing a Google sign-up, issuing and checking JWTs (including
 * the server-side session check on status and token version), the SD_TOKEN cookie, per-IP rate limits on the auth
 * endpoints, password hashing, roles, seed accounts, and the main {@code SecurityFilterChain}. The filter chain
 * collects every {@link com.swiftdrop.shared.web.PublicEndpoints} bean, so other modules open their public URLs
 * without editing it.
 *
 * <p>Public API: {@link com.swiftdrop.identity.IdentityApi}, {@link com.swiftdrop.identity.UserSummary},
 * {@link com.swiftdrop.identity.CourierSummary}, {@link com.swiftdrop.identity.Role},
 * {@link com.swiftdrop.identity.UserStatus}, and {@link com.swiftdrop.identity.MerchantProfiles} (implemented by
 * merchants, so identity never depends on merchants).
 *
 * <p>Uses: notifications, only through {@code NotificationsApi} (verification, reset and password-changed emails).
 *
 * <p>Owner: Viduranga. Database schema: {@code identity} (tables {@code users}, {@code verification_codes}).
 * Events published: none yet. Events consumed: none.
 */
@ApplicationModule(displayName = "Identity")
package com.swiftdrop.identity;

import org.springframework.modulith.ApplicationModule;
