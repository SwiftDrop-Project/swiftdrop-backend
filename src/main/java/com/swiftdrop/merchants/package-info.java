/**
 * Merchants: business profiles of MERCHANT users and the admin approval workflow (API keys come later).
 *
 * <p>Merchant sign-up starts in identity (POST /auth/register, POST /auth/complete-profile), which calls
 * {@link com.swiftdrop.identity.MerchantProfiles}; this module implements it. Approval and rejection change the user's
 * status through {@link com.swiftdrop.identity.IdentityApi}. So merchants depends on identity, never the other way.
 *
 * <p>Public API: {@link com.swiftdrop.merchants.MerchantApi}, {@link com.swiftdrop.merchants.MerchantSummary},
 * {@link com.swiftdrop.merchants.ApprovalStatus}.
 *
 * <p>Owner: Viduranga. Database schema: {@code merchants} (table {@code merchant_profiles}; {@code user_id} and
 * {@code reviewed_by} are identity user ids, no foreign keys). Events published: none yet. Events consumed: none.
 */
@ApplicationModule(displayName = "Merchants")
package com.swiftdrop.merchants;

import org.springframework.modulith.ApplicationModule;
