package com.swiftdrop.assistant.internal.application;

import com.swiftdrop.assistant.internal.config.AssistantProperties;
import com.swiftdrop.assistant.internal.provider.ChatReply;
import com.swiftdrop.assistant.internal.provider.GeminiAnswerWriter;
import com.swiftdrop.assistant.internal.provider.ProviderUnavailableException;
import com.swiftdrop.assistant.internal.provider.RulesChatProvider;
import com.swiftdrop.assistant.internal.provider.Turn;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.shared.error.FieldValidationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Answers one chat message in two steps:
 * <ol>
 *   <li>The rules provider understands the question and gets the data from SwiftDrop's APIs (tracking, pricing),
 *   building a plain answer and cards from it.</li>
 *   <li>When Gemini is configured and there is data, Gemini rewrites that answer clearly, using only the data. If
 *   Gemini fails, or its answer uses a fact that isn't in the data, the plain answer is sent.</li>
 * </ol>
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    static final int MAX_USER_MESSAGE = 500;

    private final AssistantProperties properties;
    private final RulesChatProvider rules;
    private final GeminiAnswerWriter gemini;
    private final AssistantRateLimiter rateLimiter;

    public AssistantService(AssistantProperties properties, RulesChatProvider rules, GeminiAnswerWriter gemini,
                            AssistantRateLimiter rateLimiter) {
        this.properties = properties;
        this.rules = rules;
        this.gemini = gemini;
        this.rateLimiter = rateLimiter;
    }

    /**
     * @param conversation oldest first; the last message must be the user's, at most 500 characters
     */
    public ChatReply chat(List<Turn> conversation, Caller caller) {
        Turn latest = conversation.getLast();
        if (!latest.fromUser()) {
            throw new FieldValidationException("messages", "The last message must be the user's.");
        }
        if (latest.text().length() > MAX_USER_MESSAGE) {
            throw new FieldValidationException("messages", "Keep your message under 500 characters.");
        }
        rateLimiter.check(caller.loggedIn() ? caller.userId().toString() : caller.clientKey());

        ChatReply answer = rules.reply(conversation, caller);
        // Only answers with data are rewritten; questions back to the user and help texts are sent as they are.
        if (!properties.geminiEnabled() || answer.cards().isEmpty()) {
            return answer;
        }
        try {
            return new ChatReply(gemini.write(latest.text(), answer.text(), answer.cards()), answer.cards(),
                    "gemini");
        } catch (ProviderUnavailableException e) {
            log.warn("Gemini didn't write the answer, sending the plain one: {}", e.getMessage());
            return answer;
        }
    }
}
