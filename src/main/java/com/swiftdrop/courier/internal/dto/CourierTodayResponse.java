package com.swiftdrop.courier.internal.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code GET /courier/assignments/today}.
 *
 * @param date           today in Sri Lankan time
 * @param open           tasks not completed or cancelled yet
 * @param completedToday tasks the courier completed today
 * @param assignments    in progress, then assigned (oldest first), then closed today (newest first)
 */
public record CourierTodayResponse(LocalDate date, long open, long completedToday,
                                   List<AssignmentSummaryResponse> assignments) {
}
