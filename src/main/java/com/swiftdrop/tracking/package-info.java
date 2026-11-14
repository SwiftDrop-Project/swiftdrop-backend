/**
 * Tracking: public parcel tracking by tracking number and live updates over WebSocket (STOMP), as in the design
 * document (2.4.10 "Realtime", 4.5).
 *
 * <ul>
 *   <li>{@code GET /api/v1/track/{trackingNumber}} (public, rate limited per IP): status, towns and steps so far; no
 *   personal data.</li>
 *   <li>STOMP over WebSocket: {@code /api/v1/ws/public} (anyone; {@code /topic/parcels/{trackingNumber}}) and
 *   {@code /api/v1/ws} (cookie checked; DISPATCHER and ADMIN may also follow {@code /topic/live}).</li>
 *   <li>{@code GET /api/v1/tracking/live/recent} (DISPATCHER, ADMIN): the latest changes, for the live board.</li>
 *   <li>{@code GET /api/v1/admin/tracking/parcels} and {@code .../counts} (ADMIN): every parcel's latest status with
 *   filters, from the module's own index.</li>
 * </ul>
 *
 * <p>Owner: D.M.A.I. Disanayaka (WP8, live tracking). Database schema: {@code tracking}
 * ({@code parcel_index}: each parcel's latest status, filled from events and lookups; the live board's recent events
 * stay in memory). Events published:
 * none. Events consumed: {@code ParcelStatusChanged} from RabbitMQ (exchange {@code parcel.events}), on one
 * auto-delete queue per backend instance, so the queue only fills while the outbox relay
 * runs ({@code SWIFTDROP_OUTBOX_RELAY_ENABLED=true}). Depends on delivery and shared.
 */
@ApplicationModule(displayName = "Tracking")
package com.swiftdrop.tracking;

import org.springframework.modulith.ApplicationModule;
