package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.tools.AssistantCard;
import java.util.List;

/**
 * The assistant's answer.
 *
 * @param cards    what the tools found, for the UI to show under the answer
 * @param provider which provider answered (gemini or rules)
 */
public record ChatReply(String text, List<AssistantCard> cards, String provider) {

    public ChatReply {
        cards = List.copyOf(cards);
    }
}
