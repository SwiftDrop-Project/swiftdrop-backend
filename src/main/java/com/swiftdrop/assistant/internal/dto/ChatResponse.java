package com.swiftdrop.assistant.internal.dto;

import com.swiftdrop.assistant.internal.tools.AssistantCard;
import java.util.List;

/**
 * The assistant's answer.
 *
 * @param cards    TRACKING, PRICE or PARCEL_LIST data found while answering, for the UI to show under the reply
 * @param provider {@code gemini} or {@code rules}
 */
public record ChatResponse(String reply, List<AssistantCard> cards, String provider) {
}
