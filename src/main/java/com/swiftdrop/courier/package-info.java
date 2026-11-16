/**
 * Courier: the logged-in courier's parcels: today's pickups and deliveries, one parcel with its history, status
 * updates (picked up, in transit, out for delivery, delivered with the recipient's name, failed with a reason) and
 * delivery history. Endpoints under {@code /courier/deliveries}, role COURIER only.
 *
 * <p>Stateless: no schema, no tables. Parcels, assignments, history and proof live in delivery and are read and
 * changed only through {@link com.swiftdrop.delivery.DeliveryApi}. A courier only sees parcels whose
 * {@code assignedCourierId} is their own user id; anything else answers 404, so ids of other parcels can't be probed.
 * Until a {@code DeliveryApi} implementation exists, the endpoints answer 503 SERVICE_UNAVAILABLE.
 *
 * <p>Uses: delivery ({@code DeliveryApi} and its records), shared, and identity ({@code IdentityApi}, only for the
 * temporary demo data in {@code internal.fake}). No module depends on courier.
 *
 * <p>TEMPORARY: {@code internal.fake} holds an in-memory {@code DeliveryApi} for demos, on only with
 * {@code SWIFTDROP_FAKE_DELIVERY=true}. Delete it when WP3 implements {@code DeliveryApi}.
 *
 * <p>Owner: Kalpani (WP9). Database schema: none. Events published: none (delivery publishes
 * {@code ParcelStatusChanged} for every status change). Events consumed: none.
 */
@ApplicationModule(displayName = "Courier")
package com.swiftdrop.courier;

import org.springframework.modulith.ApplicationModule;
