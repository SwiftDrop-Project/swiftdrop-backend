package com.swiftdrop.assistant.internal.tools;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelSearch;
import com.swiftdrop.delivery.ParcelSort;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Admins: find parcels by status, district, branch and booking day, through the admin parcel search
 * ({@link DeliveryApi#search}) or a branch's parcels ({@link DeliveryApi#findAtBranch}). Returns how many match and the
 * newest {@value #LIMIT}, with the public view only: tracking number, status, towns, branch and last update. Never
 * names, phone numbers, addresses or amounts.
 */
@Component
public class FindParcelsTool implements AssistantTool {

    public static final String NAME = "find_parcels";
    static final int LIMIT = 10;

    private final DeliveryApi deliveries;

    FindParcelsTool(DeliveryApi deliveries) {
        this.deliveries = deliveries;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Find parcels (admins)";
    }

    @Override
    public String description() {
        return "Find parcels by status, pickup district, drop-off district, the branch they are at, and booking days. "
                + "Gives how many match and the newest " + LIMIT + ". Admins only.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> district = Map.of("type", "string", "enum", Words.districtCodes());
        return Map.of("type", "object", "properties", Map.of(
                "status", Map.of("type", "string",
                        "enum", Arrays.stream(ParcelStatus.values()).map(Enum::name).toList()),
                "pickupDistrict", district,
                "dropOffDistrict", district,
                "branch", Map.of("type", "string", "enum", Words.districtCodes(),
                        "description", "Parcels at this branch now (status AT_BRANCH)"),
                "bookedFrom", Map.of("type", "string", "format", "date"),
                "bookedTo", Map.of("type", "string", "format", "date")));
    }

    @Override
    public boolean availableTo(Caller caller) {
        return caller.isAdmin();
    }

    @Override
    public ToolResult run(JsonNode arguments, Caller caller) {
        ParcelStatus status = null;
        String wanted = arguments.path("status").asString("").trim();
        if (!wanted.isEmpty()) {
            try {
                status = ParcelStatus.valueOf(wanted.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return ToolResult.failed("Unknown status " + wanted + ".");
            }
        }
        Optional<District> pickup = optionalDistrict(arguments, "pickupDistrict");
        Optional<District> dropOff = optionalDistrict(arguments, "dropOffDistrict");
        Optional<District> branch = optionalDistrict(arguments, "branch");
        if (pickup == null || dropOff == null || branch == null) {
            return ToolResult.failed("That isn't one of our 25 districts.");
        }
        LocalDate bookedFrom;
        LocalDate bookedTo;
        try {
            bookedFrom = date(arguments, "bookedFrom");
            bookedTo = date(arguments, "bookedTo");
        } catch (DateTimeParseException e) {
            return ToolResult.failed("Give dates as year-month-day, for example 2026-11-16.");
        }
        if (bookedFrom != null && bookedTo != null && bookedTo.isBefore(bookedFrom)) {
            return ToolResult.failed("The first day must come before the last day.");
        }

        long total;
        List<DeliverySnapshot> parcels;
        if (branch.isPresent()) {
            // Parcels at a branch are AT_BRANCH by definition; the other filters are applied to that list.
            List<DeliverySnapshot> atBranch = deliveries.findAtBranch(branch.get()).stream()
                    .filter(p -> pickup.isEmpty() || p.pickup().district() == pickup.get())
                    .filter(p -> dropOff.isEmpty() || p.recipient().district() == dropOff.get())
                    .filter(p -> bookedFrom == null || !day(p).isBefore(bookedFrom))
                    .filter(p -> bookedTo == null || !day(p).isAfter(bookedTo))
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .toList();
            total = atBranch.size();
            parcels = atBranch.stream().limit(LIMIT).toList();
        } else {
            ParcelSearch search = new ParcelSearch(null,
                    status == null ? Set.of() : Set.of(status),
                    pickup.map(Set::of).orElse(Set.of()),
                    dropOff.map(Set::of).orElse(Set.of()),
                    bookedFrom == null ? null : bookedFrom.atStartOfDay(Words.SRI_LANKA).toInstant(),
                    bookedTo == null ? null : bookedTo.plusDays(1).atStartOfDay(Words.SRI_LANKA).toInstant(),
                    null, ParcelSort.NEWEST);
            Page<DeliverySnapshot> page = deliveries.search(search, PageRequest.of(0, LIMIT));
            total = page.getTotalElements();
            parcels = page.getContent();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeliverySnapshot parcel : parcels) {
            rows.add(row(parcel));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", total);
        data.put("shown", rows.size());
        data.put("filters", filtersText(branch.isPresent() ? ParcelStatus.AT_BRANCH : status, pickup, dropOff,
                branch, bookedFrom, bookedTo));
        data.put("parcels", rows);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("parcels", rows);
        card.put("total", total);
        card.put("filters", data.get("filters"));
        return ToolResult.of(data, rows.isEmpty() ? null : new AssistantCard("PARCEL_LIST", card));
    }

    /** The search in words: "out for delivery, going to Kandy, booked 16 Nov 2026 to 19 Nov 2026". */
    static String filtersText(ParcelStatus status, Optional<District> pickup, Optional<District> dropOff,
                              Optional<District> branch, LocalDate from, LocalDate to) {
        List<String> parts = new ArrayList<>();
        if (branch.isPresent()) {
            parts.add("at the " + branch.get().displayName() + " branch");
        } else if (status != null) {
            parts.add(Words.statusLabel(status).toLowerCase(Locale.ROOT));
        }
        pickup.ifPresent(d -> parts.add("collected in " + d.displayName()));
        dropOff.ifPresent(d -> parts.add("going to " + d.displayName()));
        if (from != null && to != null) {
            parts.add(from.equals(to) ? "booked on " + Words.day(from)
                    : "booked " + Words.day(from) + " to " + Words.day(to));
        } else if (from != null) {
            parts.add("booked since " + Words.day(from));
        } else if (to != null) {
            parts.add("booked up to " + Words.day(to));
        }
        return parts.isEmpty() ? "all parcels" : String.join(", ", parts);
    }

    private static Map<String, Object> row(DeliverySnapshot parcel) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("trackingNumber", parcel.trackingNumber());
        row.put("status", parcel.status().name());
        row.put("statusText", Words.status(parcel.status(), parcel.currentBranch(), parcel.failureReason()));
        row.put("from", place(parcel.pickup()));
        row.put("to", place(parcel.recipient()));
        row.put("currentBranch", parcel.currentBranch() == null ? null : parcel.currentBranch().name());
        row.put("lastUpdate", Words.when(parcel.updatedAt()));
        row.put("updatedAt", parcel.updatedAt() == null ? null : parcel.updatedAt().toString());
        return row;
    }

    /** Town and district only, like public tracking. */
    private static String place(DeliveryContact contact) {
        if (contact == null || contact.district() == null) {
            return null;
        }
        return contact.city() == null || contact.city().isBlank() ? contact.district().displayName()
                : contact.city() + ", " + contact.district().displayName();
    }

    private static LocalDate day(DeliverySnapshot parcel) {
        return LocalDate.ofInstant(parcel.createdAt(), Words.SRI_LANKA);
    }

    /** Empty when not given, null when given but not a district. */
    private static Optional<District> optionalDistrict(JsonNode arguments, String field) {
        String value = arguments.path(field).asString("").trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Optional<District> district = Words.district(value);
        return district.isPresent() ? district : null;
    }

    private static LocalDate date(JsonNode arguments, String field) {
        String value = arguments.path(field).asString("").trim();
        return value.isEmpty() ? null : LocalDate.parse(value);
    }
}
