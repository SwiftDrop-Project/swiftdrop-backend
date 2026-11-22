/**
 * Webhooks: merchants register HTTPS URLs, and SwiftDrop sends them a signed POST when one of their parcels changes
 * status, retrying failed deliveries (see API.md "Webhooks").
 *
 * <p>Model: one subscription is one destination (a callback URL, its signing secret and one or more event types),
 * not one event. A merchant uses one subscription per server or system with all the events it needs, and several
 * subscriptions only for different destinations (at most 5 active). A callback URL can be used by only one of a
 * merchant's active subscriptions ({@code WEBHOOK_URL_IN_USE}; "same URL" is defined by {@code CallbackDestination});
 * other merchants may use the same URL.
 *
 * <p>Public API: none (endpoints only, under {@code /merchant/webhooks} and {@code /admin/webhooks}). Nothing depends
 * on webhooks.
 *
 * <p>Dependencies: delivery ({@code ParcelStatusChanged}, which also carries the merchant order reference),
 * merchants ({@code MerchantApi}: only approved merchants may subscribe), shared.
 *
 * <p>Owner: chanuka (WP6). Events published: none. Events consumed: {@code ParcelStatusChanged} from RabbitMQ queue
 * {@code webhooks.q} (declared by infrastructure; webhooks must not declare it again, because a declaration with other
 * arguments is refused by RabbitMQ). Only events of parcels with a merchant are sent. The internal event is never
 * forwarded as it is: merchants get a public payload without customer ids, actor ids or notes.
 *
 * <p>Delivery: the listener only stores one {@code PENDING} delivery per (event, subscription), so a redelivered
 * message is ignored. A scheduled worker sends due deliveries and keeps the retry state in the database (not in
 * RabbitMQ), claiming rows with {@code FOR UPDATE SKIP LOCKED} so several backends never send the same one.
 * A claim is a 60-second lease ({@code next_attempt_at} moves ahead, the row stays PENDING), so a backend that dies
 * mid-send loses nothing; no transaction or connection is held during the HTTP call. The worker runs when
 * {@code SWIFTDROP_WEBHOOKS_DISPATCHER_ENABLED=true} (the default). Before each send the URL is resolved and checked
 * again; the HTTP client then resolves it once more, so a host that changes its DNS answer within those
 * milliseconds is not caught (pinning the checked address is a possible hardening step).
 * Signing secrets are stored encrypted (they are needed in plain form to sign), never hashed and never logged.
 *
 * <p>Status: built. Subscriptions API, test ping, delivery history and manual retry ({@code /merchant/webhooks}),
 * the admin delivery list and retry ({@code /admin/webhooks/deliveries}), the {@code webhooks.q} consumer (events
 * from before a subscription was created are not replayed to it) and the sending worker with retries (a test ping is
 * tried once). A ping or a retry only makes a row PENDING and due now; the worker sends it. Two changes to one
 * subscription at the same moment: the second gets 409 WEBHOOK_CHANGED (turning off twice is still fine).
 *
 * <p>Database schema {@code webhooks} (no foreign keys; merchant ids are identity user ids):
 * <ul>
 *   <li>{@code webhooks.subscriptions}: one row per merchant URL, with its event types and encrypted secret.</li>
 *   <li>{@code webhooks.deliveries}: one row per event and subscription (unique), with the exact payload sent,
 *       attempts, next attempt time and the last result, plus the parcel id, tracking number and merchant order
 *       number (null for test pings) so the delivery history can be searched.</li>
 * </ul>
 */
@ApplicationModule(displayName = "Webhooks")
package com.swiftdrop.webhooks;

import org.springframework.modulith.ApplicationModule;
