package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.tools.AssistantCard;
import com.swiftdrop.assistant.internal.tools.AssistantTools;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.assistant.internal.tools.ServiceReportTool;
import com.swiftdrop.assistant.internal.tools.ToolResult;
import com.swiftdrop.assistant.internal.tools.Words;
import com.swiftdrop.shared.reference.District;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Understands the question and gets the data: recognises tracking numbers, price questions, "my parcels" and district
 * questions with patterns, calls the tools (SwiftDrop's tracking and pricing APIs) and builds a plain answer from
 * their data. Gemini may then rewrite that answer ({@code GeminiAnswerWriter}); without Gemini it is sent as is.
 */
@Component
public class RulesChatProvider {

    static final String NAME = "rules";

    private static final Pattern TRACKING_NUMBER =
            Pattern.compile("\\bSD\\s*\\d{4}\\s*\\d{4}\\s*\\d{2}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_WORDS = Pattern.compile(
            "\\b(how much|price|prices|cost|costs|fee|fees|charge|charges|rate|rates|quote|estimate)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WEIGHT = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:kg|kgs|kilo|kilos|kilogram|kilograms)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*$");
    private static final Pattern SIZE = Pattern.compile(
            "(\\d{1,3})\\s*(?:cm)?\\s*(?:[x×*]|by)\\s*(\\d{1,3})\\s*(?:cm)?\\s*(?:[x×*]|by)\\s*(\\d{1,3})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MY_PARCELS = Pattern.compile(
            "\\b(my|mine)\\b.*\\b(parcels?|deliver(?:y|ies)|orders?|bookings?|packages?|shipments?)\\b"
                    + "|\\bwhere(?:'s| is| are)\\s+my\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTRICTS = Pattern.compile(
            "\\bdistricts?\\b|\\bwhich (?:areas|places|cities|towns)\\b|\\bwhere do you (?:deliver|go|operate)\\b"
                    + "|\\b(?:areas|places) (?:you )?(?:cover|deliver)", Pattern.CASE_INSENSITIVE);

    /** Price follow-ups start with this, so the next message is read as the answer. */
    static final String ASK_PRICE_DETAILS = "To estimate the price, tell me ";

    private final AssistantTools tools;
    private final AdminAnswers admin;

    public RulesChatProvider(AssistantTools tools) {
        this.tools = tools;
        this.admin = new AdminAnswers(tools);
    }

    /**
     * @param conversation oldest first, ending with the user's new message
     */
    public ChatReply reply(List<Turn> conversation, Caller caller) {
        String latest = conversation.getLast().text();
        List<AssistantCard> cards = new ArrayList<>();

        List<String> numbers = TRACKING_NUMBER.matcher(latest).results().map(m -> m.group()).limit(3).toList();
        if (!numbers.isEmpty()) {
            return new ChatReply(track(numbers, caller, cards), cards, NAME);
        }
        if (isAdmin(caller) && (ReportRequest.isReport(latest)
                || (ReportRequest.ASK_PERIOD.equals(previousAnswer(conversation))
                        && ReportRequest.namesPeriod(latest)))) {
            return new ChatReply(report(latest, caller, cards), cards, NAME);
        }
        if (isAdmin(caller)) {
            String answer = admin.answer(latest, caller, cards);
            if (answer != null) {
                return new ChatReply(answer, cards, NAME);
            }
        }
        String priceText = priceConversation(conversation);
        if (priceText != null) {
            return new ChatReply(price(priceText, latest, previousAnswer(conversation), caller, cards), cards, NAME);
        }
        if (MY_PARCELS.matcher(latest).find()) {
            return new ChatReply(myParcels(caller, cards), cards, NAME);
        }
        if (DISTRICTS.matcher(latest).find()) {
            return new ChatReply(districts(), cards, NAME);
        }
        return new ChatReply(help(caller, latest), cards, NAME);
    }

    // ---- Tracking ----

    private String track(List<String> numbers, Caller caller, List<AssistantCard> cards) {
        List<String> answers = new ArrayList<>();
        for (String number : numbers) {
            ToolResult result = tools.run("track_parcel", args(Map.of("trackingNumber", number)), caller);
            Map<String, Object> data = result.data();
            if (result.error()) {
                answers.add(String.valueOf(data.get("error")));
            } else if (Boolean.FALSE.equals(data.get("found"))) {
                answers.add("I couldn't find a parcel with the number " + number.toUpperCase(Locale.ROOT)
                        + ". Check the number on your booking email and try again. If it's right, "
                        + Words.CONTACT_TEAM + ".");
            } else {
                answers.add(data.get("trackingNumber") + " is " + data.get("statusText") + ". It's going from "
                        + data.get("from") + " to " + data.get("to") + ". Last update: " + data.get("lastUpdate")
                        + ".");
                cards.add(result.card());
            }
        }
        return String.join("\n\n", answers);
    }

    // ---- Price ----

    /**
     * The user messages of the price question being asked, or null when the user isn't asking about a price: the
     * latest message if it asks about a price, or, when the assistant just asked for missing details, the messages
     * from the original question on.
     */
    private static String priceConversation(List<Turn> conversation) {
        int last = conversation.size() - 1;
        if (PRICE_WORDS.matcher(conversation.get(last).text()).find()) {
            return conversation.get(last).text();
        }
        String previous = previousAnswer(conversation);
        // Our own follow-ups, or any question (Gemini words its own, and rules answer when Gemini times out).
        if (previous == null || !(previous.startsWith(ASK_PRICE_DETAILS)
                || previous.strip().endsWith("?"))) {
            return null;
        }
        for (int i = last - 1; i >= Math.max(0, last - 8); i--) {
            Turn turn = conversation.get(i);
            if (turn.fromUser() && PRICE_WORDS.matcher(turn.text()).find()) {
                return conversation.subList(i, conversation.size()).stream()
                        .filter(Turn::fromUser)
                        .map(Turn::text)
                        .collect(Collectors.joining("\n"));
            }
        }
        return null;
    }

    private static String previousAnswer(List<Turn> conversation) {
        for (int i = conversation.size() - 2; i >= 0; i--) {
            if (!conversation.get(i).fromUser()) {
                return conversation.get(i).text();
            }
        }
        return null;
    }

    private String price(String text, String latest, String previousAnswer, Caller caller,
                         List<AssistantCard> cards) {
        String weight = null;
        Matcher weightMatcher = WEIGHT.matcher(text);
        while (weightMatcher.find()) {
            weight = weightMatcher.group(1).replace(',', '.');
        }
        Matcher bare = BARE_NUMBER.matcher(latest);
        if (weight == null && bare.matches() && previousAnswer != null && previousAnswer.contains("weight")) {
            weight = bare.group(1).replace(',', '.');
        }

        District pickup = null;
        District dropOff = null;
        List<District> unplaced = new ArrayList<>();
        for (Words.Mention mention : Words.districtsIn(text)) {
            String before = text.substring(Math.max(0, mention.start() - 10), mention.start())
                    .toLowerCase(Locale.ROOT);
            if (before.matches("(?s).*\\bfrom\\s+(the\\s+)?$")) {
                pickup = mention.district();
            } else if (before.matches("(?s).*\\b(to|into|for)\\s+(the\\s+)?$")) {
                dropOff = mention.district();
            } else {
                unplaced.add(mention.district());
            }
        }
        for (District district : unplaced) {
            if (pickup == null && district != dropOff) {
                pickup = district;
            } else if (dropOff == null && district != pickup) {
                dropOff = district;
            }
        }

        Matcher size = SIZE.matcher(text);
        Integer[] dimensions = null;
        while (size.find()) {
            dimensions = new Integer[] {Integer.valueOf(size.group(1)), Integer.valueOf(size.group(2)),
                    Integer.valueOf(size.group(3))};
        }

        if (weight != null && (new java.math.BigDecimal(weight).compareTo(new java.math.BigDecimal("0.01")) < 0
                || new java.math.BigDecimal(weight).compareTo(new java.math.BigDecimal("50")) > 0)) {
            return "SwiftDrop takes parcels from 0.01 kg to 50 kg.";
        }

        List<String> missing = new ArrayList<>();
        if (pickup == null) {
            missing.add("the district we collect it from");
        }
        if (dropOff == null) {
            missing.add("the district it's going to");
        }
        if (weight == null) {
            missing.add("its weight in kg");
        }
        if (dimensions == null) {
            // The size and weight pick the vehicle, whose fee is added for the pickup and the delivery.
            missing.add("its size (length x width x height in cm)");
        }
        if (!missing.isEmpty()) {
            return ASK_PRICE_DETAILS + joinWithAnd(missing) + ". For example: \"2 kg from Colombo to Kandy, "
                    + "30 x 20 x 10 cm\".";
        }

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("pickupDistrict", pickup.name());
        request.put("dropOffDistrict", dropOff.name());
        request.put("weightKg", weight);
        request.put("lengthCm", dimensions[0]);
        request.put("widthCm", dimensions[1]);
        request.put("heightCm", dimensions[2]);
        ToolResult result = tools.run("estimate_price", args(request), caller);
        Map<String, Object> data = result.data();
        if (result.error()) {
            return String.valueOf(data.get("error"));
        }
        cards.add(result.card());
        String kg = ((java.math.BigDecimal) data.get("weightKg")).stripTrailingZeros().toPlainString();
        String route = kg + " kg from " + data.get("pickupDistrict") + " to "
                + data.get("dropOffDistrict");
        if (!"COMPLETE".equals(data.get("status"))) {
            return "Sending " + route + " needs a manual quote: the parcel is too big or heavy for our bikes, "
                    + "tuk-tuks and lorries. For a quote, " + Words.CONTACT_TEAM + ".";
        }
        return "Sending " + route + " (" + data.get("sizeCm") + " cm) costs about " + data.get("totalText")
                + ". It goes by " + data.get("vehicleText") + ": " + breakdown(data) + ".";
    }

    /** "pickup by bike Rs. 150.00, delivery by bike Rs. 150.00, weight Rs. 50.00 and ..." from the fee lines. */
    private static String breakdown(Map<String, Object> data) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) data.get("lines");
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            String amount = Words.rupees((java.math.BigDecimal) line.get("amount"));
            String label = switch (String.valueOf(line.get("component"))) {
                case "PICKUP" -> "pickup by " + data.get("vehicleText");
                case "DELIVERY" -> "delivery by " + data.get("vehicleText");
                case "LINEHAUL" -> data.get("distanceKm") == null ? "transport between branches"
                        : "transport between branches (" + number(data.get("distanceKm")) + " km)";
                // The billable weight: the actual or the volumetric weight (L x W x H / 5000), rounded up.
                case "WEIGHT" -> data.get("billableWeightKg") == null ? "weight"
                        : "weight (billed as " + number(data.get("billableWeightKg")) + " kg)";
                case "BASE" -> "base fee";
                case "COD" -> "cash on delivery";
                default -> String.valueOf(line.get("component")).toLowerCase(Locale.ROOT);
            };
            parts.add(label + " " + amount);
        }
        return joinWithAnd(parts);
    }

    /** "3" for 3.00, "122.6" for 122.60. */
    private static String number(Object value) {
        return value instanceof java.math.BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(value);
    }

    // ---- My parcels ----

    private String myParcels(Caller caller, List<AssistantCard> cards) {
        if (!caller.loggedIn()) {
            return "Tell me the tracking number (it starts with SD and is in your booking email) and I'll find the "
                    + "parcel. Or log in to see all your parcels.";
        }
        if (!caller.ownsParcels()) {
            return "Only customers and merchants have parcels to list. Give me a tracking number to track a parcel.";
        }
        ToolResult result = tools.run("my_parcels", args(Map.of()), caller);
        if (result.error()) {
            return String.valueOf(result.data().get("error"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parcels = (List<Map<String, Object>>) result.data().get("parcels");
        if (parcels.isEmpty()) {
            return "You don't have any parcels yet.";
        }
        cards.add(result.card());
        String lines = parcels.stream()
                .map(p -> "- " + Words.trackingNumber(String.valueOf(p.get("trackingNumber"))) + " to " + p.get("to")
                        + ": " + p.get("statusText"))
                .collect(Collectors.joining("\n"));
        return (parcels.size() == 1 ? "Here is your latest parcel:\n" : "Here are your latest " + parcels.size()
                + " parcels:\n") + lines;
    }

    // ---- Admin reports ----

    private static boolean isAdmin(Caller caller) {
        return caller.isAdmin();
    }

    @SuppressWarnings("unchecked")
    private String report(String latest, Caller caller, List<AssistantCard> cards) {
        ReportRequest request = ReportRequest.read(latest, LocalDate.now(Words.SRI_LANKA));
        if (request.period() == null) {
            return ReportRequest.ASK_PERIOD;
        }
        ToolResult result = tools.run(ServiceReportTool.NAME,
                args(Map.of("period", request.period().name(), "date", request.day().toString())), caller);
        Map<String, Object> data = result.data();
        if (result.error()) {
            return String.valueOf(data.get("error"));
        }
        cards.add(result.card());

        StringBuilder text = new StringBuilder();
        text.append(data.get("title")).append(" for ").append(data.get("rangeText")).append(": ")
                .append(data.get("booked")).append(" parcels booked, ").append(data.get("delivered"))
                .append(" delivered and ").append(data.get("failed")).append(" failed");
        if (data.get("successRate") != null) {
            text.append(", a ").append(data.get("successRate")).append("% delivery success rate");
        }
        text.append('.');

        Map<String, Object> previous = (Map<String, Object>) data.get("previous");
        Map<String, Map<String, Object>> changes = (Map<String, Map<String, Object>>) data.get("changes");
        text.append(" Compared with ").append(previous.get("text")).append(": bookings ")
                .append(changeText(changes.get("booked"))).append(", deliveries ")
                .append(changeText(changes.get("delivered"))).append(", failures ")
                .append(changeText(changes.get("failed"))).append('.');

        Map<String, Object> busiest = (Map<String, Object>) data.get("busiestDay");
        if (busiest != null) {
            text.append(" The busiest day was ").append(busiest.get("dateText")).append(" with ")
                    .append(busiest.get("booked")).append(" bookings.");
        }
        List<Map<String, Object>> pickups = (List<Map<String, Object>>) data.get("topPickupDistricts");
        if (!pickups.isEmpty()) {
            text.append(" Most parcels were collected in ").append(pickups.getFirst().get("district")).append(" (")
                    .append(pickups.getFirst().get("count")).append(").");
        }
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) data.get("failureReasons");
        if (!reasons.isEmpty()) {
            text.append(" The most common failure reason: ").append(reasons.getFirst().get("reason")).append(" (")
                    .append(reasons.getFirst().get("count")).append(").");
        }
        return text.toString();
    }

    static String changeText(Map<String, Object> change) {
        long difference = ((Number) change.get("difference")).longValue();
        if (difference == 0) {
            return "unchanged";
        }
        String text = (difference > 0 ? "up " : "down ") + Math.abs(difference);
        Object percent = change.get("percent");
        if (percent instanceof java.math.BigDecimal value) {
            text += " (" + value.abs().stripTrailingZeros().toPlainString() + "%)";
        }
        return text;
    }

    // ---- Districts and help ----

    private static String districts() {
        String names = Arrays.stream(District.values()).map(District::displayName).collect(Collectors.joining(", "));
        return "SwiftDrop picks up from and delivers to all 25 districts of Sri Lanka, each with its own branch: "
                + names + ".";
    }

    /** The start of the answer to a question SwiftDrop has no data for. It is never sent to Gemini. */
    static final String NO_DATA = "I don't have that information in SwiftDrop's system, so I can't answer it. For "
            + "anything else, " + Words.CONTACT_TEAM + ". ";

    /**
     * A question no tool has data for: a fixed answer that says so and what the assistant can do. It has no cards,
     * so it is never rewritten by Gemini, which would otherwise answer from what it knows itself.
     */
    private static String help(Caller caller, String latest) {
        String greeting = latest.trim().matches("(?i)(hi|hello|hey|good (morning|afternoon|evening))\\W*")
                ? "Hello! " : NO_DATA;
        if (isAdmin(caller)) {
            return greeting + "I can give you daily, weekly and monthly service reports, figures such as parcels "
                    + "delivered, the success rate, the busiest day, top districts and failure reasons, how many "
                    + "parcels are in each status now, account counts, and find parcels by status, district or "
                    + "branch. I can also track a parcel and estimate a price. Try: \"How many parcels are out for "
                    + "delivery?\", \"Success rate this month\" or \"Show failed parcels going to Kandy\".";
        }
        List<String> can = new ArrayList<>(List.of("track a parcel from its tracking number",
                "estimate a delivery price", "list the districts we deliver to"));
        if (caller.ownsParcels()) {
            can.add("show your parcels");
        }
        return greeting + "I can " + joinWithAnd(can)
                + ". Try: \"How much to send 2 kg from Colombo to Kandy, 30 x 20 x 10 cm?\"";
    }

    static String joinWithAnd(List<String> parts) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    static ObjectNode args(Map<String, Object> values) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        values.forEach((key, value) -> {
            if (value instanceof Integer number) {
                node.put(key, number);
            } else {
                node.put(key, String.valueOf(value));
            }
        });
        return node;
    }
}
