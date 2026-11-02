/**
 * Webhooks: merchant webhook subscriptions and signed delivery of parcel events to merchant URLs.
 *
 * <p>Owner: TBD. Database schema: webhooks. Events published: none. Events consumed: {@code ParcelStatusChanged} from RabbitMQ queue {@code webhooks.q}.
 *
 * <p>Not built yet. Other modules may use only the top-level types of this package.
 */
@ApplicationModule(displayName = "Webhooks")
package com.swiftdrop.webhooks;

import org.springframework.modulith.ApplicationModule;
