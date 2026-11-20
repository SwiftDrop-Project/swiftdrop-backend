package com.swiftdrop.assistant.internal.tools;

/**
 * Structured data shown under an answer: a tracking card, a price breakdown or a list of parcels.
 *
 * @param type TRACKING, PRICE, PARCEL_LIST, REPORT or FACTS (see {@link #facts})
 */
public record AssistantCard(String type, Object data) {

    /** One figure of a FACTS card: "Couriers" / "3". */
    public record Fact(String label, String value) {
    }

    /** A short list of figures (admin answers): a title, an optional subtitle such as the period, and the figures. */
    public static AssistantCard facts(String title, String subtitle, java.util.List<Fact> facts) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("title", title);
        data.put("subtitle", subtitle);
        data.put("facts", facts);
        return new AssistantCard("FACTS", data);
    }
}
