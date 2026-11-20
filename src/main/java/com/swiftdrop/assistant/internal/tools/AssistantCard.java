package com.swiftdrop.assistant.internal.tools;

/**
 * Structured data shown under an answer: a tracking card, a price breakdown or a list of parcels.
 *
 * @param type TRACKING, PRICE or PARCEL_LIST
 */
public record AssistantCard(String type, Object data) {
}
