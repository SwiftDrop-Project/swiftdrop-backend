/**
 * Technical wiring only, no business logic: global error handling, correlation IDs, reference-data endpoints,
 * OpenAPI, CORS, the /api/v1 prefix and the RabbitMQ topology. No other module depends on it; everything lives in
 * {@code internal}.
 *
 * <ul>
 *   <li>{@code internal.web}: GlobalExceptionHandler, CorrelationIdFilter, GET /meta/districts</li>
 *   <li>{@code internal.config}: OpenAPI, CORS, /api/v1 prefix (Jackson defaults; rate limiting later)</li>
 *   <li>{@code internal.messaging}: RabbitMQ topic exchange {@code parcel.events}, routing key
 *       {@code parcel.status.changed}, durable queues {@code notifications.q} and {@code webhooks.q}</li>
 * </ul>
 *
 * <p>Owner: TBD. Database schema: none. Events: none published or consumed.
 */
@ApplicationModule(displayName = "Infrastructure")
package com.swiftdrop.infrastructure;

import org.springframework.modulith.ApplicationModule;
