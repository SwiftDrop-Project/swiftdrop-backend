package com.swiftdrop.courier.internal.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code GET /courier/deliveries/today}.
 *
 * @param date  today in Sri Lankan time
 * @param toDo  parcels not finished yet
 * @param done  parcels delivered or failed today
 * @param items pickups, then deliveries, then finished ones
 */
public record CourierTodayResponse(LocalDate date, long toDo, long done, List<CourierDeliveryResponse> items) {
}
