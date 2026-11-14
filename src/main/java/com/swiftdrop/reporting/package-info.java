/**
 * Reporting: figures and reports for the admin portal, read through other modules' Apis.
 *
 * <p>Built so far:
 * <ul>
 *   <li>the admin overview ({@code GET /admin/reports/overview}): accounts per role and merchants waiting for approval
 *       (identity), parcels created, delivered and failed today and in the last 7 days (delivery), and the newest
 *       accounts;</li>
 *   <li>All Parcels ({@code /admin/parcels}): summary figures, the filtered parcel list, one parcel with its sender
 *       account and status history, and a CSV export. Read only: admins do not assign or reassign couriers;
 *       dispatchers do that in the dispatch module.</li>
 * </ul>
 *
 * <p>Uses: identity ({@code IdentityApi}), delivery ({@code DeliveryApi}) and merchants ({@code MerchantApi}).
 * Nothing depends on reporting.
 *
 * <p>Owner: WP4. Database schema: none. Events published: none. Events consumed: none.
 * Other modules may use only the top-level types of this package (there are none yet).
 */
@ApplicationModule(displayName = "Reporting")
package com.swiftdrop.reporting;

import org.springframework.modulith.ApplicationModule;
