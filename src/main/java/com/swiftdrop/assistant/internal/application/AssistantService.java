package com.swiftdrop.assistant.internal.application;

import com.swiftdrop.assistant.internal.config.AssistantProperties;
import com.swiftdrop.assistant.internal.provider.ChatReply;
import com.swiftdrop.assistant.internal.provider.GeminiChatProvider;
import com.swiftdrop.assistant.internal.provider.ProviderUnavailableException;
import com.swiftdrop.assistant.internal.provider.RulesChatProvider;
import com.swiftdrop.assistant.internal.provider.Turn;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.shared.error.FieldValidationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Answers one chat message: Gemini when it is configured, the rules provider otherwise or when Gemini fails. */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    static final int MAX_USER_MESSAGE = 500;

    private final AssistantProperties properties;
    private final GeminiChatProvider gemini;
    private final RulesChatProvider rules;
    private final AssistantRateLimiter rateLimiter;

    public AssistantService(AssistantProperties properties, GeminiChatProvider gemini, RulesChatProvider rules,
                            AssistantRateLimiter rateLimiter) {
        this.properties = properties;
        this.gemini = gemini;
        this.rules = rules;
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

        if (properties.geminiEnabled()) {
            try {
                return gemini.reply(conversation, caller);
            } catch (ProviderUnavailableException e) {
                log.warn("Gemini unavailable, answering with rules: {}", e.getMessage());
            }
        }
        return rules.reply(conversation, caller);
    }
}
