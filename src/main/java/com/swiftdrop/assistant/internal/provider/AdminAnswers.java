package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.tools.AccountCountsTool;
import com.swiftdrop.assistant.internal.tools.AssistantCard;
import com.swiftdrop.assistant.internal.tools.AssistantTools;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.assistant.internal.tools.FindParcelsTool;
import com.swiftdrop.assistant.internal.tools.ParcelCountsTool;
import com.swiftdrop.assistant.internal.tools.ServiceReportTool;
import com.swiftdrop.assistant.internal.tools.ServiceReportTool.Period;
import com.swiftdrop.assistant.internal.tools.ToolResult;
import com.swiftdrop.assistant.internal.tools.Words;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.shared.reference.District;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Admin questions other than full reports, each answered from one admin tool:
 * <ul>
 *   <li>parcels in a status now: "How many parcels are out for delivery?" ({@code parcel_counts})</li>
 *   <li>accounts: "How many couriers do we have?", "Any merchants waiting for approval?" ({@code account_counts})</li>
 *   <li>figures of a period: "How many parcels were delivered yesterday?", "Success rate this month?", "Busiest day
 *   this week?", "Top districts last month?", "Why did deliveries fail this week?" ({@code service_report})</li>
 *   <li>finding parcels: "Show failed parcels going to Kandy", "Parcels at the Colombo branch", "Which parcels were
 *   booked today?" ({@code find_parcels})</li>
 * </ul>
 */
final class AdminAnswers {

    private static final int I = Pattern.CASE_INSENSITIVE;

    private static final Pattern ACCOUNTS = Pattern.compile("\\b(couriers?|riders?|drivers?|customers?|merchants?"
            + "|sellers?|dispatchers?|admins?|administrators?|users?|accounts?|staff)\\b", I);
    private static final Pattern APPROVAL = Pattern.compile("\\b(approv\\w*|pending|waiting)\\b", I);
    private static final Pattern HOW_MANY = Pattern.compile(
            "\\b(how many|number of|count of|count|total|do we have|are there|is there|any)\\b", I);
    private static final Pattern PARCELS = Pattern.compile(
            "\\b(parcels?|deliver(?:y|ies)|orders?|packages?|shipments?|bookings?)\\b", I);
    private static final Pattern LIST = Pattern.compile(
            "\\b(show|list|find|which|what are|give me|see|display|search)\\b", I);

    /** A price question, which the price estimate answers ("rate" alone isn't one: "success rate"). */
    private static final Pattern PRICE = Pattern.compile("\\b(how much|price|prices|cost|costs|fee|fees|charges?"
            + "|quote|estimate)\\b|\\d\\s*(kg|kgs|kilos?|kilograms?)\\b", I);
    private static final Pattern DELIVERY_RATE = Pattern.compile("\\bdelivery\\s+rates?\\b", I);
    private static final Pattern RATE =Pattern.compile("\\b(success|failure|delivery|completion)\\s+rate\\b", I);
    private static final Pattern TOP_DISTRICTS = Pattern.compile("\\b(top|most|busiest|popular|main|biggest)\\b.*"
            + "\\b(districts?|areas?|cities|city|towns?|places?|regions?|routes?)\\b"
            + "|\\b(districts?|areas?)\\b.*\\b(most|busiest)\\b", I);
    private static final Pattern BUSIEST_DAY = Pattern.compile("\\b(busiest|peak|best)\\s+day\\b", I);
    private static final Pattern REASONS = Pattern.compile(
            "\\bfail\\w*\\s+reasons?\\b|\\breasons?\\b.*\\bfail|\\bwhy\\b.*\\bfail", I);
    private static final Pattern BOOKED = Pattern.compile(
            "\\b(booked|bookings?|new parcels|new orders|created|placed|sent)\\b", I);

    /** Status words, checked in this order ("waiting to be picked up" before "picked up"). */
    private static final List<Map.Entry<ParcelStatus, Pattern>> STATUSES = List.of(
            Map.entry(ParcelStatus.CREATED, Pattern.compile("\\bwaiting (for|to be) (a )?(pick ?up|picked up|"
                    + "collected|collection|courier)|\\bnot (yet )?(been )?(picked up|collected)|\\bawaiting "
                    + "(pick ?up|collection)|\\bunassigned\\b|\\bpending (pick ?up|collection)", I)),
            Map.entry(ParcelStatus.OUT_FOR_DELIVERY, Pattern.compile("\\bout for delivery\\b", I)),
            Map.entry(ParcelStatus.IN_TRANSIT, Pattern.compile("\\bin transit\\b|\\bbetween branches\\b", I)),
            Map.entry(ParcelStatus.AT_BRANCH, Pattern.compile("\\bbranch(es)?\\b", I)),
            Map.entry(ParcelStatus.FAILED, Pattern.compile("\\bfail(ed|ures?)?\\b|\\bunsuccessful\\b"
                    + "|\\bnot delivered\\b", I)),
            Map.entry(ParcelStatus.PICKED_UP, Pattern.compile("\\bpicked up\\b|\\bcollected\\b", I)),
            Map.entry(ParcelStatus.DELIVERED, Pattern.compile("\\bdelivered\\b|\\bcompleted deliveries\\b", I)));

    private enum Figure { BOOKED, DELIVERED, FAILED, SUCCESS_RATE, BUSIEST_DAY, TOP_DISTRICTS, FAILURE_REASONS }

    private final AssistantTools tools;

    AdminAnswers(AssistantTools tools) {
        this.tools = tools;
    }

    /** The answer to an admin's question, or null when it isn't one of these questions. */
    /**
     * Whether the admin asks for a delivery fee: price words or a weight, or a "delivery rate" between districts
     * ("delivery rate this month" is the success rate).
     */
    static boolean isPriceQuestion(String text) {
        return PRICE.matcher(text).find()
                || DELIVERY_RATE.matcher(text).find() && !Words.districtsIn(text).isEmpty();
    }

    String answer(String text, Caller caller, List<AssistantCard> cards) {
        if (isPriceQuestion(text)) {
            return null;
        }
        LocalDate today = LocalDate.now(Words.SRI_LANKA);
        boolean howMany = HOW_MANY.matcher(text).find();
        boolean period = ReportRequest.hasPeriod(text);
        ParcelStatus status = status(text);

        if (ACCOUNTS.matcher(text).find() && !PARCELS.matcher(text).find()
                && (howMany || APPROVAL.matcher(text).find() || LIST.matcher(text).find())) {
            return accounts(text, caller, cards);
        }
        if (RATE.matcher(text).find()) {
            return figure(Figure.SUCCESS_RATE, text, today, caller, cards);
        }
        if (TOP_DISTRICTS.matcher(text).find()) {
            return figure(Figure.TOP_DISTRICTS, text, today, caller, cards);
        }
        if (BUSIEST_DAY.matcher(text).find()) {
            return figure(Figure.BUSIEST_DAY, text, today, caller, cards);
        }
        if (REASONS.matcher(text).find()) {
            return figure(Figure.FAILURE_REASONS, text, today, caller, cards);
        }
        boolean aboutParcels = PARCELS.matcher(text).find() || status != null;
        boolean placeNamed = !Words.districtsIn(text).isEmpty();
        if (LIST.matcher(text).find() && aboutParcels && !howMany) {
            return find(text, status, today, caller, cards);
        }
        if (howMany && aboutParcels) {
            if (BOOKED.matcher(text).find() && !placeNamed) {
                return figure(Figure.BOOKED, text, today, caller, cards);
            }
            if (period && !placeNamed && (status == ParcelStatus.DELIVERED || status == ParcelStatus.FAILED)) {
                return figure(status == ParcelStatus.DELIVERED ? Figure.DELIVERED : Figure.FAILED, text, today,
                        caller, cards);
            }
            if (placeNamed || (period && status != null)) {
                return find(text, status, today, caller, cards);
            }
            if (period) {
                // "How many deliveries today?" means delivered; "How many parcels this week?" means booked.
                boolean deliveries = text.toLowerCase(Locale.ROOT).matches("(?s).*\bdeliveries\b.*");
                return figure(deliveries ? Figure.DELIVERED : Figure.BOOKED, text, today, caller, cards);
            }
            return statusNow(status, caller, cards);
        }
        if (period && (status == ParcelStatus.DELIVERED || status == ParcelStatus.FAILED)) {
            return figure(status == ParcelStatus.DELIVERED ? Figure.DELIVERED : Figure.FAILED, text, today, caller,
                    cards);
        }
        return null;
    }

    static ParcelStatus status(String text) {
        for (Map.Entry<ParcelStatus, Pattern> entry : STATUSES) {
            if (entry.getValue().matcher(text).find()) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ---- Parcels by status now ----

    private String statusNow(ParcelStatus status, Caller caller, List<AssistantCard> cards) {
        ToolResult result = tools.run(ParcelCountsTool.NAME, RulesChatProvider.args(Map.of()), caller);
        if (result.error()) {
            return String.valueOf(result.data().get("error"));
        }
        cards.add(result.card());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> counts = (List<Map<String, Object>>) result.data().get("counts");
        Object total = result.data().get("total");
        if (status != null) {
            Object count = counts.stream().filter(row -> status.name().equals(row.get("status")))
                    .map(row -> row.get("count")).findFirst().orElse(0L);
            return count + " " + parcels(count) + " " + nowText(status, count) + " right now, out of " + total
                    + " parcels in all.";
        }
        return "Right now there are " + total + " parcels in all: " + counts.stream()
                .map(row -> row.get("count") + " " + String.valueOf(row.get("label")).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", ")) + ".";
    }

    private static String nowText(ParcelStatus status, Object count) {
        String verb = "1".equals(String.valueOf(count)) ? "is" : "are";
        return switch (status) {
            case CREATED -> verb + " waiting for pickup";
            case PICKED_UP -> verb + " picked up and on the way to a branch";
            case AT_BRANCH -> verb + " at a branch";
            case IN_TRANSIT -> verb + " in transit between branches";
            case OUT_FOR_DELIVERY -> verb + " out for delivery";
            case DELIVERED -> verb + " delivered";
            case FAILED -> verb + " failed deliveries";
        };
    }

    // ---- Accounts ----

    private String accounts(String text, Caller caller, List<AssistantCard> cards) {
        ToolResult result = tools.run(AccountCountsTool.NAME, RulesChatProvider.args(Map.of()), caller);
        if (result.error()) {
            return String.valueOf(result.data().get("error"));
        }
        cards.add(result.card());
        Map<String, Object> data = result.data();
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        if (lower.matches("(?s).*\\bmerchants?\\b.*") && APPROVAL.matcher(text).find()) {
            Object pending = data.get("pendingMerchants");
            return pending + " " + ("1".equals(String.valueOf(pending)) ? "merchant is" : "merchants are")
                    + " waiting for approval, out of " + data.get("merchants") + " merchant accounts.";
        }
        addIf(parts, lower, "\\b(couriers?|riders?|drivers?)\\b", data.get("couriers"), "courier");
        addIf(parts, lower, "\\bcustomers?\\b", data.get("customers"), "customer");
        addIf(parts, lower, "\\b(merchants?|sellers?)\\b", data.get("merchants"), "merchant");
        addIf(parts, lower, "\\bdispatchers?\\b", data.get("dispatchers"), "dispatcher");
        addIf(parts, lower, "\\b(admins?|administrators?)\\b", data.get("admins"), "admin");
        if (parts.isEmpty()) {
            return "SwiftDrop has " + data.get("total") + " accounts: " + data.get("customers") + " customers, "
                    + data.get("merchants") + " merchants (" + data.get("pendingMerchants")
                    + " waiting for approval), " + data.get("couriers") + " couriers, " + data.get("dispatchers")
                    + " dispatchers and " + data.get("admins") + " admins.";
        }
        return "SwiftDrop has " + RulesChatProvider.joinWithAnd(parts) + ".";
    }

    private static void addIf(List<String> parts, String lower, String regex, Object count, String role) {
        if (lower.matches("(?s).*" + regex + ".*")) {
            parts.add(count + " " + role + ("1".equals(String.valueOf(count)) ? " account" : " accounts"));
        }
    }

    // ---- Figures of a period ----

    @SuppressWarnings("unchecked")
    private String figure(Figure figure, String text, LocalDate today, Caller caller, List<AssistantCard> cards) {
        ReportRequest request = ReportRequest.read(text, today);
        Period period = request.period() == null ? Period.DAILY : request.period();
        if (figure == Figure.BUSIEST_DAY && period == Period.DAILY) {
            period = Period.WEEKLY;
        }
        ToolResult result = tools.run(ServiceReportTool.NAME,
                RulesChatProvider.args(Map.of("period", period.name(), "date", request.day().toString())), caller);
        Map<String, Object> data = result.data();
        if (result.error()) {
            return String.valueOf(data.get("error"));
        }
        String range = String.valueOf(data.get("rangeText"));
        Map<String, Object> previous = (Map<String, Object>) data.get("previous");
        Map<String, Map<String, Object>> changes = (Map<String, Map<String, Object>>) data.get("changes");
        List<AssistantCard.Fact> facts = new ArrayList<>(List.of(
                fact("Booked", data.get("booked")), fact("Delivered", data.get("delivered")),
                fact("Failed", data.get("failed")),
                new AssistantCard.Fact("Success rate",
                        data.get("successRate") == null ? "-" : data.get("successRate") + "%")));
        String title;
        String answer;
        switch (figure) {
            case BOOKED, DELIVERED, FAILED -> {
                String key = figure.name().toLowerCase(Locale.ROOT);
                title = figure == Figure.BOOKED ? "Parcels booked" : figure == Figure.DELIVERED
                        ? "Parcels delivered" : "Failed deliveries";
                Object count = data.get(key);
                String verb = figure == Figure.BOOKED ? "booked" : figure == Figure.DELIVERED ? "delivered"
                        : "failed";
                answer = count + " " + parcels(count) + " " + ("1".equals(String.valueOf(count)) ? "was " : "were ")
                        + verb + " for " + range + ". " + compared(changes.get(key), previous) + ".";
            }
            case SUCCESS_RATE -> {
                title = "Delivery success rate";
                answer = data.get("successRate") == null
                        ? "No deliveries were finished for " + range + ", so there is no success rate yet."
                        : "The delivery success rate for " + range + " was " + data.get("successRate") + "%: "
                                + data.get("delivered") + " delivered and " + data.get("failed") + " failed."
                                + (previous.get("successRate") == null ? ""
                                        : " For " + previous.get("text") + " it was " + previous.get("successRate")
                                                + "%.");
            }
            case BUSIEST_DAY -> {
                title = "Busiest day";
                Map<String, Object> busiest = (Map<String, Object>) data.get("busiestDay");
                answer = busiest == null ? "No parcels were booked for " + range + "."
                        : "The busiest day for " + range + " was " + busiest.get("dateText") + ", with "
                                + busiest.get("booked") + " parcels booked.";
                if (busiest != null) {
                    facts.add(fact("Busiest day: " + busiest.get("dateText"), busiest.get("booked")));
                }
            }
            case TOP_DISTRICTS -> {
                title = "Top districts";
                List<Map<String, Object>> pickups = (List<Map<String, Object>>) data.get("topPickupDistricts");
                List<Map<String, Object>> dropOffs = (List<Map<String, Object>>) data.get("topDropOffDistricts");
                facts.clear();
                pickups.forEach(row -> facts.add(fact("From " + row.get("district"), row.get("count"))));
                dropOffs.forEach(row -> facts.add(fact("To " + row.get("district"), row.get("count"))));
                answer = pickups.isEmpty() ? "No parcels were booked for " + range + "."
                        : "For " + range + ", most parcels were collected in " + topText(pickups)
                                + ", and most were going to " + topText(dropOffs) + ".";
            }
            default -> {
                title = "Failure reasons";
                List<Map<String, Object>> reasons = (List<Map<String, Object>>) data.get("failureReasons");
                facts.clear();
                reasons.forEach(row -> facts.add(fact(capitalise(String.valueOf(row.get("reason"))),
                        row.get("count"))));
                answer = reasons.isEmpty() ? "No deliveries failed for " + range + "."
                        : "Deliveries that failed for " + range + ", by reason: " + reasons.stream()
                                .map(row -> row.get("reason") + " (" + row.get("count") + ")")
                                .collect(Collectors.joining("; ")) + ".";
            }
        }
        cards.add(AssistantCard.facts(title, range, facts));
        return answer;
    }

    private static String compared(Map<String, Object> change, Map<String, Object> previous) {
        String text = RulesChatProvider.changeText(change);
        return text.equals("unchanged") ? "That's the same as " + previous.get("text")
                : "That's " + text + " compared with " + previous.get("text");
    }

    private static String topText(List<Map<String, Object>> rows) {
        return RulesChatProvider.joinWithAnd(rows.stream().limit(3)
                .map(row -> row.get("district") + " (" + row.get("count") + ")").toList());
    }

    // ---- Finding parcels ----

    private String find(String text, ParcelStatus status, LocalDate today, Caller caller, List<AssistantCard> cards) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (status != null && status != ParcelStatus.AT_BRANCH) {
            request.put("status", status.name());
        }
        for (Words.Mention mention : Words.districtsIn(text)) {
            String before = text.substring(Math.max(0, mention.start() - 12), mention.start()).toLowerCase(Locale.ROOT);
            String after = text.substring(mention.end(), Math.min(text.length(), mention.end() + 8))
                    .toLowerCase(Locale.ROOT);
            District district = mention.district();
            if (after.matches("(?s)\\s*branch.*") || status == ParcelStatus.AT_BRANCH) {
                request.put("branch", district.name());
            } else if (before.matches("(?s).*\\b(from|collected in|picked up in|sent from)\\s+(the\\s+)?$")) {
                request.put("pickupDistrict", district.name());
            } else {
                request.put("dropOffDistrict", district.name());
            }
        }
        if (status == ParcelStatus.AT_BRANCH && !request.containsKey("branch")) {
            request.put("status", ParcelStatus.AT_BRANCH.name());
        }
        if (ReportRequest.hasPeriod(text)) {
            ReportRequest when = ReportRequest.read(text, today);
            Period period = when.period() == null ? Period.DAILY : when.period();
            LocalDate end = period.end(when.day());
            request.put("bookedFrom", period.start(when.day()).toString());
            request.put("bookedTo", (end.isAfter(today) ? today : end).toString());
        }
        ToolResult result = tools.run(FindParcelsTool.NAME, RulesChatProvider.args(request), caller);
        Map<String, Object> data = result.data();
        if (result.error()) {
            return String.valueOf(data.get("error"));
        }
        long total = ((Number) data.get("total")).longValue();
        String filters = String.valueOf(data.get("filters"));
        if (total == 0) {
            return "No parcels match: " + filters + ".";
        }
        cards.add(result.card());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("parcels");
        String lines = rows.stream()
                .map(p -> "- " + Words.trackingNumber(String.valueOf(p.get("trackingNumber"))) + " to " + p.get("to")
                        + ": " + p.get("statusText"))
                .collect(Collectors.joining("\n"));
        return total + " " + parcels(total) + " " + ("1".equals(String.valueOf(total)) ? "matches" : "match") + " ("
                + filters + ")." + (total > rows.size() ? " The newest " + rows.size() + ":" : "") + "\n" + lines;
    }

    // ---- Words ----

    private static String parcels(Object count) {
        return "1".equals(String.valueOf(count)) ? "parcel" : "parcels";
    }

    private static AssistantCard.Fact fact(String label, Object value) {
        return new AssistantCard.Fact(label, String.valueOf(value));
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
