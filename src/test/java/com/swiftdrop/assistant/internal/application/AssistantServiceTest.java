package com.swiftdrop.assistant.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swiftdrop.assistant.internal.config.AssistantProperties;
import com.swiftdrop.assistant.internal.provider.ChatReply;
import com.swiftdrop.assistant.internal.provider.GeminiChatProvider;
import com.swiftdrop.assistant.internal.provider.ProviderUnavailableException;
import com.swiftdrop.assistant.internal.provider.RulesChatProvider;
import com.swiftdrop.assistant.internal.provider.Turn;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.shared.error.FieldValidationException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which provider answers, and the checks before either is asked. */
class AssistantServiceTest {

    private final GeminiChatProvider gemini = mock(GeminiChatProvider.class);
    private final RulesChatProvider rules = mock(RulesChatProvider.class);
    private final Caller visitor = Caller.anonymous("127.0.0.1");
    private final List<Turn> question = List.of(new Turn(true, "Hi"));

    private AssistantService service(String provider, String key) {
        AssistantProperties properties = new AssistantProperties(provider, 20, 5,
                new AssistantProperties.Gemini(key, "m", "http://unused", Duration.ofSeconds(1)));
        return new AssistantService(properties, gemini, rules, new AssistantRateLimiter(properties));
    }

    @Test
    void geminiAnswersWhenConfigured() {
        when(gemini.reply(any(), any())).thenReturn(new ChatReply("from gemini", List.of(), "gemini"));

        assertThat(service("gemini", "key").chat(question, visitor).text()).isEqualTo("from gemini");
        verify(rules, never()).reply(any(), any());
    }

    @Test
    void rulesAnswerWhenGeminiFails() {
        when(gemini.reply(any(), any())).thenThrow(new ProviderUnavailableException("quota"));
        when(rules.reply(any(), any())).thenReturn(new ChatReply("from rules", List.of(), "rules"));

        assertThat(service("gemini", "key").chat(question, visitor).provider()).isEqualTo("rules");
    }

    @Test
    void rulesAnswerWithoutAKeyOrWhenChosen() {
        when(rules.reply(any(), any())).thenReturn(new ChatReply("from rules", List.of(), "rules"));

        service("gemini", " ").chat(question, visitor);
        service("rules", "key").chat(question, visitor);

        verify(gemini, never()).reply(any(), any());
    }

    @Test
    void theLastMessageMustBeAShortUserMessage() {
        AssistantService service = service("rules", "");

        assertThatThrownBy(() -> service.chat(List.of(new Turn(false, "Hello")), visitor))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.chat(List.of(new Turn(true, "x".repeat(501))), visitor))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.fieldErrors().get("messages")).contains("500 characters"));
    }
}
