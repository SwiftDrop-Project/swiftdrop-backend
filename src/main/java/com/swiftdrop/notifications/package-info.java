/**
 * Notifications: emails (and later SMS) to users: account emails and parcel status updates.
 *
 * <p>Owner: TBD. Database schema: notifications. Events published: none. Events consumed: {@code ParcelStatusChanged} from RabbitMQ queue {@code notifications.q}.
 *
 * <p>Not built yet. Other modules may use only the top-level types of this package.
 */
@ApplicationModule(displayName = "Notifications")
package com.swiftdrop.notifications;

import org.springframework.modulith.ApplicationModule;
