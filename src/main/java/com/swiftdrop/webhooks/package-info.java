/**
 * Webhooks: merchants register HTTPS URLs, and SwiftDrop sends them a signed POST when one of their parcels changes
 * status, retrying failed deliveries (see API.md "Webhooks").
 *
 * <p>Public API: none (endpoints only, under {@code /merchant/webhooks} and {@code /admin/webhooks}). Nothing depends
 * on webhooks.
 *
 * <p>Dependencies: delivery ({@code ParcelStatusChanged}; {@code DeliveryApi.findById} for the merchant order
 * reference until the event carries it), merchants ({@code MerchantApi}: only approved merchants may subscribe),
 * shared.
 *
 * <p>Owner: chanuka (WP6). Events published: none. Events consumed: {@code ParcelStatusChanged} from RabbitMQ queue
 * {@code webhooks.q} (declared by infrastructure; webhooks must not declare it again, because a declaration with other
 * arguments is refused by RabbitMQ). Only events of parcels with a merchant are sent. The internal event is never
 * forwarded as it is: merchants get a public payload without customer ids, actor ids or notes.
 *
 * <p>Delivery: the listener only stores one {@code PENDING} delivery per (event, subscription), so a redelivered
 * message is ignored. A scheduled worker sends due deliveries and keeps the retry state in the database (not in
 * RabbitMQ), claiming rows with {@code FOR UPDATE SKIP LOCKED} so several backends never send the same one.
 * Signing secrets are stored encrypted (they are needed in plain form to sign), never hashed and never logged.
 *
 * <p>Status: subscriptions API ({@code /merchant/webhooks}) and the {@code webhooks.q} consumer are built (events
 * become PENDING deliveries; events from before a subscription was created are not replayed to it). Sending,
 * retries, the test ping, delivery history and the admin endpoints are in progress.
 *
 * <p>Database schema {@code webhooks} (no foreign keys; merchant ids are identity user ids):
 * <ul>
 *   <li>{@code webhooks.subscriptions}: one row per merchant URL, with its event types and encrypted secret.</li>
 *   <li>{@code webhooks.deliveries}: one row per event and subscription (unique), with the exact payload sent,
 *       attempts, next attempt time and the last result.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Webhooks")
package com.swiftdrop.webhooks;

import org.springframework.modulith.ApplicationModule;
