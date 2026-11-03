/**
 * Tracking: public parcel tracking by tracking number and live updates over Server-Sent Events.
 *
 * <p>Owner: TBD. Database schema: none. Events published: none. Events consumed: {@code ParcelStatusChanged} (to push live updates).
 *
 * <p>Not built yet. Other modules may use only the top-level types of this package.
 */
@ApplicationModule(displayName = "Tracking")
package com.swiftdrop.tracking;

import org.springframework.modulith.ApplicationModule;
