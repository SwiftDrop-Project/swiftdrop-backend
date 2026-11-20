package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.tools.AssistantCard;
import com.swiftdrop.assistant.internal.tools.AssistantTools;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.assistant.internal.tools.ToolResult;
import com.swiftdrop.assistant.internal.tools.Words;
import com.swiftdrop.shared.reference.District;
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
 * Answers without an AI model: recognises tracking numbers, price questions, "my parcels" and district questions with
 * patterns, and calls the same tools as the model. Used when Gemini isn't configured or fails, and in tests.
 */
@Component
public class RulesChatProvider implements ChatProvider {

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
            "(\\d{1,3})\\s*(?:cm)?\\s*[x×*]\\s*(\\d{1,3})\\s*(?:cm)?\\s*[x×*]\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern MY_PARCELS = Pattern.compile(
            "\\b(my|mine)\\b.*\\b(parcels?|deliver(?:y|ies)|orders?|bookings?|packages?|shipments?)\\b"
                    + "|\\bwhere(?:'s| is| are)\\s+my\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTRICTS = Pattern.compile(
            "\\bdistricts?\\b|\\bwhich (?:areas|places|cities|towns)\\b|\\bwhere do you (?:deliver|go|operate)\\b"
                    + "|\\b(?:areas|places) (?:you )?(?:cover|deliver)", Pattern.CASE_INSENSITIVE);

    /** Price follow-ups start with this, so the next message is read as the answer. */
    static final String ASK_PRICE_DETAILS = "To estimate the price, tell me ";
    /** A provisional price ends with this, so a size in the next message completes it. */
    static final String ASK_SIZE = "for the full price.";

    private final AssistantTools tools;

    public RulesChatProvider(AssistantTools tools) {
        this.tools = tools;
    }

    @Override
    public ChatReply reply(List<Turn> conversation, Caller caller) {
        String latest = conversation.getLast().text();
        List<AssistantCard> cards = new ArrayList<>();

        List<String> numbers = TRACKING_NUMBER.matcher(latest).results().map(m -> m.group()).limit(3).toList();
        if (!numbers.isEmpty()) {
            return new ChatReply(track(numbers, caller, cards), cards, NAME);
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
                        + ". Check the number on your booking email and try again.");
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
        if (previous == null || !(previous.startsWith(ASK_PRICE_DETAILS) || previous.endsWith(ASK_SIZE)
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
        if (!missing.isEmpty()) {
            return ASK_PRICE_DETAILS + joinWithAnd(missing) + ". For example: \"2 kg from Colombo to Kandy\".";
        }

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("pickupDistrict", pickup.name());
        request.put("dropOffDistrict", dropOff.name());
        request.put("weightKg", weight);
        Matcher size = SIZE.matcher(text);
        Integer[] dimensions = null;
        while (size.find()) {
            dimensions = new Integer[] {Integer.valueOf(size.group(1)), Integer.valueOf(size.group(2)),
                    Integer.valueOf(size.group(3))};
        }
        if (dimensions != null) {
            request.put("lengthCm", dimensions[0]);
            request.put("widthCm", dimensions[1]);
            request.put("heightCm", dimensions[2]);
        }
        ToolResult result = tools.run("estimate_price", args(request), caller);
        Map<String, Object> data = result.data();
        if (result.error()) {
            return String.valueOf(data.get("error"));
        }
        cards.add(result.card());
        String kg = ((java.math.BigDecimal) data.get("weightKg")).stripTrailingZeros().toPlainString();
        String route = kg + " kg from " + data.get("pickupDistrict") + " to "
                + data.get("dropOffDistrict");
        return switch (String.valueOf(data.get("status"))) {
            case "COMPLETE" -> "Sending " + route + " costs about " + data.get("totalText") + ".";
            case "PROVISIONAL" -> "Sending " + route + " costs about " + data.get("totalText")
                    + ", plus the pickup and delivery charges, which depend on the parcel's size. Tell me its size "
                    + "(for example 30 x 20 x 10 cm) " + ASK_SIZE;
            default -> "Sending " + route + " needs a manual quote: the parcel is too big or heavy for our bikes, "
                    + "tuk-tuks and lorries. Please contact SwiftDrop support.";
        };
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

    // ---- Districts and help ----

    private static String districts() {
        String names = Arrays.stream(District.values()).map(District::displayName).collect(Collectors.joining(", "));
        return "SwiftDrop picks up from and delivers to all 25 districts of Sri Lanka, each with its own branch: "
                + names + ".";
    }

    private static String help(Caller caller, String latest) {
        String greeting = latest.trim().matches("(?i)(hi|hello|hey|good (morning|afternoon|evening))\\W*")
                ? "Hello! " : "I can only help with SwiftDrop deliveries. ";
        return greeting + "I can track a parcel from its tracking number, estimate a delivery price, list the "
                + "districts we deliver to" + (caller.ownsParcels() ? ", and show your parcels" : "")
                + ". Try: \"How much to send 2 kg from Colombo to Kandy?\"";
    }

    private static String joinWithAnd(List<String> parts) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    private static ObjectNode args(Map<String, Object> values) {
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
