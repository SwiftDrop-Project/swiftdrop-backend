package com.swiftdrop.assistant.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swiftdrop.assistant.internal.config.AssistantProperties;
import com.swiftdrop.assistant.internal.provider.ChatReply;
import com.swiftdrop.assistant.internal.provider.GeminiAnswerWriter;
import com.swiftdrop.assistant.internal.provider.ProviderUnavailableException;
import com.swiftdrop.assistant.internal.provider.RulesChatProvider;
import com.swiftdrop.assistant.internal.provider.Turn;
import com.swiftdrop.assistant.internal.tools.AssistantCard;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.shared.error.FieldValidationException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SwiftDrop's code gets the data; Gemini only rewrites answers that have data. */
class AssistantServiceTest {

    private final RulesChatProvider rules = mock(RulesChatProvider.class);
    private final GeminiAnswerWriter gemini = mock(GeminiAnswerWriter.class);
    private final Caller visitor = Caller.anonymous("127.0.0.1");
    private final List<Turn> question = List.of(new Turn(true, "Where is SD2611874236?"));
    private final List<AssistantCard> parcel = List.of(new AssistantCard("TRACKING", Map.of("status", "CREATED")));

    private AssistantService service(String provider, String key) {
        AssistantProperties properties = new AssistantProperties(provider, 20,
                new AssistantProperties.Gemini(key, "m", "http://unused", Duration.ofSeconds(1)));
        return new AssistantService(properties, rules, gemini, new AssistantRateLimiter(properties));
    }

    @Test
    void geminiRewritesAnAnswerThatHasData() {
        when(rules.reply(any(), any())).thenReturn(new ChatReply("plain", parcel, "rules"));
        when(gemini.write(anyString(), anyString(), any())).thenReturn("clear");

        ChatReply reply = service("gemini", "key").chat(question, visitor);

        assertThat(reply.text()).isEqualTo("clear");
        assertThat(reply.cards()).isEqualTo(parcel);
        assertThat(reply.provider()).isEqualTo("gemini");
        verify(gemini).write("Where is SD2611874236?", "plain", parcel);
    }

    @Test
    void thePlainAnswerIsSentWhenGeminiFails() {
        when(rules.reply(any(), any())).thenReturn(new ChatReply("plain", parcel, "rules"));
        when(gemini.write(anyString(), anyString(), any())).thenThrow(new ProviderUnavailableException("timeout"));

        ChatReply reply = service("gemini", "key").chat(question, visitor);

        assertThat(reply.text()).isEqualTo("plain");
        assertThat(reply.provider()).isEqualTo("rules");
    }

    @Test
    void answersWithoutDataAreNotRewritten() {
        when(rules.reply(any(), any())).thenReturn(new ChatReply("Which district?", List.of(), "rules"));

        assertThat(service("gemini", "key").chat(question, visitor).text()).isEqualTo("Which district?");
        verify(gemini, never()).write(anyString(), anyString(), any());
    }

    @Test
    void aQuestionWithoutDataGetsTheFixedAnswerNotAnLlmOne() {
        // The real rules provider with no tools: nothing in SwiftDrop answers a refund question.
        AssistantProperties properties = new AssistantProperties("gemini", 20,
                new AssistantProperties.Gemini("key", "m", "http://unused", Duration.ofSeconds(1)));
        AssistantService service = new AssistantService(properties,
                new RulesChatProvider(new com.swiftdrop.assistant.internal.tools.AssistantTools(List.of())), gemini,
                new AssistantRateLimiter(properties));

        ChatReply reply = service.chat(List.of(new Turn(true, "What is your refund policy?")), visitor);

        assertThat(reply.text()).contains("don't have that information", "support@swiftdrop.lk");
        assertThat(reply.cards()).isEmpty();
        assertThat(reply.provider()).isEqualTo("rules");
        verify(gemini, never()).write(anyString(), anyString(), any());
    }

    @Test
    void geminiIsNotUsedWithoutAKeyOrWhenRulesAreChosen() {
        when(rules.reply(any(), any())).thenReturn(new ChatReply("plain", parcel, "rules"));

        service("gemini", " ").chat(question, visitor);
        service("rules", "key").chat(question, visitor);

        verify(gemini, never()).write(anyString(), anyString(), any());
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
