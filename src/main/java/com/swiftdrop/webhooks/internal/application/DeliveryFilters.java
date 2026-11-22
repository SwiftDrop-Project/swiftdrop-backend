package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.webhooks.internal.domain.DeliveryStatus;

/**
 * The optional filters of a delivery list, as they arrive in the query string (API.md "Webhooks"): all may be null or
 * blank. {@link DeliveryCriteria#parse} checks them and turns them into a query.
 *
 * @param eventType a wire name such as {@code parcel.delivered} or {@code webhook.ping}
 * @param search    a tracking number, part of the merchant's order number, or a delivery, event or parcel id
 * @param from      first day, as {@code 2026-11-01} (Sri Lankan time, included)
 * @param to        last day, as {@code 2026-11-15} (Sri Lankan time, included)
 */
public record DeliveryFilters(DeliveryStatus status, String eventType, String search, String from, String to) {

    /** No filter at all. */
    public static final DeliveryFilters NONE = new DeliveryFilters(null, null, null, null, null);
}
