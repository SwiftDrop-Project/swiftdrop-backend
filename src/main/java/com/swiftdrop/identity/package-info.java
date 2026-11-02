/**
 * Identity: user accounts and authentication.
 *
 * <p>Register, log in, log out, current user ({@code /auth/me}), "Continue with Google" (OIDC), completing a Google
 * sign-up, issuing and checking JWTs, the SD_TOKEN cookie, password hashing, roles, seed accounts, and the main
 * {@code SecurityFilterChain}. The filter chain collects every {@link com.swiftdrop.shared.web.PublicEndpoints} bean,
 * so other modules open their public URLs without editing it.
 *
 * <p>Public API: {@link com.swiftdrop.identity.IdentityApi}, {@link com.swiftdrop.identity.UserSummary},
 * {@link com.swiftdrop.identity.CourierSummary}, {@link com.swiftdrop.identity.Role},
 * {@link com.swiftdrop.identity.UserStatus}, and {@link com.swiftdrop.identity.MerchantProfiles} (implemented by
 * merchants, so identity never depends on merchants).
 *
 * <p>Owner: Viduranga. Database schema: {@code identity} (table {@code users}).
 * Events published: none yet. Events consumed: none.
 */
@ApplicationModule(displayName = "Identity")
package com.swiftdrop.identity;

import org.springframework.modulith.ApplicationModule;
