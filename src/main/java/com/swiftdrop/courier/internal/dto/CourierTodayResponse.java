package com.swiftdrop.courier.internal.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code GET /courier/deliveries/today}.
 *
 * @param date  today in Sri Lankan time
 * @param toDo  active parcels (not completed yet)
 * @param done  parcels completed today (each on its own, whatever the rest of its task is doing)
 * @param items open pickups, then the other active parcels, then the completed ones (newest first)
 */
public record CourierTodayResponse(LocalDate date, long toDo, long done, List<CourierDeliveryResponse> items) {
}
