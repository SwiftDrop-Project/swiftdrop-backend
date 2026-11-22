package com.swiftdrop.courier.internal.application;

import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.shared.error.FieldValidationException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * The optional filters of a courier's history (GET /courier/deliveries/history), applied to the courier's finished
 * entries before paging, together with the tracking-number search:
 * <ul>
 *   <li>{@code type}: PICKUP or DELIVERY (the courier's two kinds of work)</li>
 *   <li>{@code outcome}: how the entry finished, one of the outcomes a history entry can have (handed over,
 *       delivered, returned, not collected, not taken out); no new outcomes</li>
 *   <li>{@code completedFrom} / {@code completedTo}: days (yyyy-MM-dd) in Sri Lankan time, both included, compared with
 *       when that parcel's own step finished (not its task)</li>
 * </ul>
 * Every value is optional; a blank one means no filter. A bad value is a field error naming it.
 */
public record HistoryFilter(AssignmentType type, ItemOutcome outcome, LocalDate completedFrom, LocalDate completedTo) {

    public static final HistoryFilter NONE = new HistoryFilter(null, null, null, null);

    /** The outcomes a history entry can have (an entry is listed once its step is completed). */
    static final Set<ItemOutcome> HISTORY_OUTCOMES = Set.of(ItemOutcome.HANDED_OVER, ItemOutcome.DELIVERED,
            ItemOutcome.RETURNED, ItemOutcome.NOT_COLLECTED, ItemOutcome.NOT_LOADED);

    /** Reads the raw query values, checking each. */
    public static HistoryFilter of(String type, String outcome, String completedFrom, String completedTo) {
        LocalDate from = date("completedFrom", completedFrom);
        LocalDate to = date("completedTo", completedTo);
        if (from != null && to != null && to.isBefore(from)) {
            throw new FieldValidationException("completedTo", "Choose an end date on or after the start date.");
        }
        return new HistoryFilter(type(type), outcome(outcome), from, to);
    }

    /** Whether this finished entry passes every filter that is set. */
    boolean matches(CourierTask entry) {
        if (type != null && entry.type() != type) {
            return false;
        }
        if (outcome != null && entry.outcome() != outcome) {
            return false;
        }
        Instant finished = entry.completedAt();
        if (completedFrom != null && (finished == null || finished.isBefore(startOf(completedFrom)))) {
            return false;
        }
        return completedTo == null || (finished != null && finished.isBefore(startOf(completedTo.plusDays(1))));
    }

    boolean isEmpty() {
        return type == null && outcome == null && completedFrom == null && completedTo == null;
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(CourierDay.ZONE).toInstant();
    }

    private static AssignmentType type(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "PICKUP" -> AssignmentType.PICKUP;
            case "DELIVERY" -> AssignmentType.DELIVERY;
            default -> throw new FieldValidationException("type", "Choose pickups or deliveries.");
        };
    }

    private static ItemOutcome outcome(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            ItemOutcome parsed = ItemOutcome.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (HISTORY_OUTCOMES.contains(parsed)) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // Reported below.
        }
        throw new FieldValidationException("outcome", "Choose one of the listed outcomes.");
    }

    private static LocalDate date(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new FieldValidationException(field, "Use a date like 2026-11-21.");
        }
    }
}
