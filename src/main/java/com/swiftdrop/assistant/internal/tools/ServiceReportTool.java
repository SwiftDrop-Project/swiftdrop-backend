package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryStats;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserRoleCounts;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.tracking.ParcelBreakdown;
import com.swiftdrop.tracking.TrackingApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The service report for admins: a day, a week (Monday to Sunday) or a calendar month. Every figure comes from
 * SwiftDrop's own APIs: parcels booked, delivered and failed per day ({@link DeliveryApi#stats}), parcels in each
 * status now ({@link DeliveryApi#countByStatus}), districts and failure reasons ({@link TrackingApi#breakdown}) and
 * accounts ({@link IdentityApi#getUserRoleCounts}). A period that hasn't ended is counted up to today and compared
 * with the same days of the period before.
 */
@Component
public class ServiceReportTool implements AssistantTool {

    public static final String NAME = "service_report";

    public enum Period {
        DAILY("Daily report", "the day before"),
        WEEKLY("Weekly report", "the same days of the week before"),
        MONTHLY("Monthly report", "the same days of the month before");

        final String title;
        final String previousText;

        Period(String title, String previousText) {
            this.title = title;
            this.previousText = previousText;
        }

        /** The first day of the period that contains {@code day}: the day, its Monday or the 1st of its month. */
        public LocalDate start(LocalDate day) {
            return switch (this) {
                case DAILY -> day;
                case WEEKLY -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case MONTHLY -> day.withDayOfMonth(1);
            };
        }

        /** The last day of the period that contains {@code day}. */
        public LocalDate end(LocalDate day) {
            return switch (this) {
                case DAILY -> day;
                case WEEKLY -> start(day).plusDays(6);
                case MONTHLY -> day.with(TemporalAdjusters.lastDayOfMonth());
            };
        }
    }

    private static final int TOP = 5;

    private final DeliveryApi deliveries;
    private final TrackingApi tracking;
    private final IdentityApi identity;
    private final Clock clock;

    @Autowired
    public ServiceReportTool(DeliveryApi deliveries, TrackingApi tracking, IdentityApi identity) {
        this(deliveries, tracking, identity, Clock.system(Words.SRI_LANKA));
    }

    ServiceReportTool(DeliveryApi deliveries, TrackingApi tracking, IdentityApi identity, Clock clock) {
        this.deliveries = deliveries;
        this.tracking = tracking;
        this.identity = identity;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Service report (admins)";
    }

    @Override
    public String description() {
        return "A daily, weekly (Monday to Sunday) or monthly report of SwiftDrop's service: parcels booked, delivered "
                + "and failed, success rate, change from the period before, busiest day, districts, failure reasons, "
                + "parcels in each status now and accounts. Admins only.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "period", Map.of("type", "string", "enum", List.of("DAILY", "WEEKLY", "MONTHLY")),
                        "date", Map.of("type", "string", "format", "date",
                                "description", "Any day in the period (yyyy-MM-dd); today when left out")),
                "required", List.of("period"));
    }

    @Override
    public boolean availableTo(Caller caller) {
        return caller.isAdmin();
    }

    @Override
    public ToolResult run(JsonNode arguments, Caller caller) {
        Period period;
        try {
            period = Period.valueOf(arguments.path("period").asString("").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ToolResult.failed("Choose a daily, weekly or monthly report.");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate day;
        String date = arguments.path("date").asString("").trim();
        try {
            day = date.isEmpty() ? today : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return ToolResult.failed("Give the date as year-month-day, for example " + today + ".");
        }
        Map<String, Object> data = report(period, day, today);
        if (data == null) {
            return ToolResult.failed("That period hasn't started yet. Reports cover today and earlier.");
        }
        return ToolResult.of(data, new AssistantCard("REPORT", data));
    }

    /** The report of the period that contains {@code day}, or null when it starts after today. */
    Map<String, Object> report(Period period, LocalDate day, LocalDate today) {
        LocalDate from = period.start(day);
        LocalDate to = period.end(day);
        if (from.isAfter(today)) {
            return null;
        }
        LocalDate countedTo = to.isAfter(today) ? today : to;
        long days = ChronoUnit.DAYS.between(from, countedTo);
        LocalDate previousFrom = switch (period) {
            case DAILY -> from.minusDays(1);
            case WEEKLY -> from.minusWeeks(1);
            case MONTHLY -> from.minusMonths(1);
        };
        LocalDate previousTo = previousFrom.plusDays(days);
        if (period == Period.MONTHLY && previousTo.getMonth() != previousFrom.getMonth()) {
            previousTo = previousFrom.with(TemporalAdjusters.lastDayOfMonth());
        }

        DeliveryStats stats = deliveries.stats(from, countedTo);
        DeliveryStats previous = deliveries.stats(previousFrom, previousTo);
        ParcelBreakdown breakdown = tracking.breakdown(from, countedTo);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", period.name());
        data.put("title", period.title);
        data.put("from", from.toString());
        data.put("to", to.toString());
        data.put("countedTo", countedTo.toString());
        data.put("partial", countedTo.isBefore(to));
        data.put("rangeText", rangeText(period, from, to, countedTo));
        data.put("booked", stats.totalCreated());
        data.put("delivered", stats.totalDelivered());
        data.put("failed", stats.totalFailed());
        data.put("successRate", successRate(stats));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("from", previousFrom.toString());
        before.put("to", previousTo.toString());
        before.put("text", period.previousText + " (" + range(previousFrom, previousTo) + ")");
        before.put("booked", previous.totalCreated());
        before.put("delivered", previous.totalDelivered());
        before.put("failed", previous.totalFailed());
        before.put("successRate", successRate(previous));
        data.put("previous", before);
        data.put("changes", Map.of(
                "booked", change(stats.totalCreated(), previous.totalCreated()),
                "delivered", change(stats.totalDelivered(), previous.totalDelivered()),
                "failed", change(stats.totalFailed(), previous.totalFailed())));

        if (period != Period.DAILY) {
            data.put("perDay", stats.perDay().stream().map(count -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", count.date().toString());
                row.put("dateText", Words.shortDay(count.date()));
                row.put("booked", count.created());
                row.put("delivered", count.delivered());
                row.put("failed", count.failed());
                return row;
            }).toList());
            stats.perDay().stream()
                    .filter(count -> count.created() > 0)
                    .max(Comparator.comparingLong(DeliveryStats.DailyCount::created))
                    .ifPresent(busiest -> data.put("busiestDay", Map.of(
                            "date", busiest.date().toString(),
                            "dateText", Words.shortDay(busiest.date()),
                            "booked", busiest.created())));
        }

        data.put("topPickupDistricts", top(breakdown.pickupDistricts()));
        data.put("topDropOffDistricts", top(breakdown.dropOffDistricts()));
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (Map.Entry<FailureReason, Long> entry : breakdown.failureReasons().entrySet()) {
            reasons.add(Map.of("reason", Words.failureReason(entry.getKey()), "count", entry.getValue()));
        }
        data.put("failureReasons", reasons);

        List<Map<String, Object>> statusNow = new ArrayList<>();
        Map<ParcelStatus, Long> counts = deliveries.countByStatus();
        for (ParcelStatus status : ParcelStatus.values()) {
            statusNow.add(Map.of("status", status.name(), "label", Words.statusLabel(status),
                    "count", counts.getOrDefault(status, 0L)));
        }
        data.put("statusNow", statusNow);

        UserRoleCounts users = identity.getUserRoleCounts();
        Map<String, Object> accounts = new LinkedHashMap<>();
        accounts.put("total", users.totalUsers());
        accounts.put("customers", users.totalCustomers());
        accounts.put("merchants", users.totalMerchants());
        accounts.put("couriers", users.totalCouriers());
        accounts.put("dispatchers", users.totalDispatchers());
        accounts.put("pendingMerchants", users.pendingMerchants());
        data.put("accountsNow", accounts);
        return data;
    }

    private static String rangeText(Period period, LocalDate from, LocalDate to, LocalDate countedTo) {
        String range = period == Period.DAILY ? Words.day(from) : range(from, to);
        return countedTo.isBefore(to) ? range + " (up to " + Words.day(countedTo) + ")" : range;
    }

    private static String range(LocalDate from, LocalDate to) {
        return from.equals(to) ? Words.day(from) : Words.day(from) + " to " + Words.day(to);
    }

    /** delivered / (delivered + failed) as a percentage with one decimal, or null when neither happened. */
    static BigDecimal successRate(DeliveryStats stats) {
        long finished = stats.totalDelivered() + stats.totalFailed();
        if (finished == 0) {
            return null;
        }
        return BigDecimal.valueOf(stats.totalDelivered() * 100.0 / finished).setScale(1, RoundingMode.HALF_UP);
    }

    /** The difference from the period before, and as a percentage (null when the period before had none). */
    static Map<String, Object> change(long now, long before) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("difference", now - before);
        change.put("percent", before == 0 ? null
                : BigDecimal.valueOf((now - before) * 100.0 / before).setScale(1, RoundingMode.HALF_UP));
        return change;
    }

    private static List<Map<String, Object>> top(Map<District, Long> counts) {
        return counts.entrySet().stream()
                .limit(TOP)
                .map(entry -> Map.<String, Object>of("district", entry.getKey().displayName(),
                        "count", entry.getValue()))
                .toList();
    }
}
