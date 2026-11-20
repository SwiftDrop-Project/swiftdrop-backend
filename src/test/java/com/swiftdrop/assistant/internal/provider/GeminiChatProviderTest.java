package com.swiftdrop.assistant.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swiftdrop.assistant.internal.config.AssistantProperties;
import com.swiftdrop.assistant.internal.tools.AssistantCard;
import com.swiftdrop.assistant.internal.tools.AssistantTool;
import com.swiftdrop.assistant.internal.tools.AssistantTools;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.assistant.internal.tools.ToolResult;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The function-calling loop with Gemini stubbed: no network, no Spring. */
class GeminiChatProviderTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Deque<String> answers = new ArrayDeque<>();
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<JsonNode> toolArguments = new ArrayList<>();

    private final AssistantTool echo = new AssistantTool() {
        public String name() {
            return "echo_tool";
        }

        public String title() {
            return "Echo";
        }

        public String description() {
            return "Echoes its input";
        }

        public Map<String, Object> parameters() {
            return Map.of("type", "object", "properties", Map.of("word", Map.of("type", "string")));
        }

        public ToolResult run(JsonNode arguments, Caller caller) {
            toolArguments.add(arguments);
            return ToolResult.of(Map.of("echo", arguments.path("word").asString("")),
                    new AssistantCard("TRACKING", Map.of("word", arguments.path("word").asString(""))));
        }
    };

    private final AssistantTool ownersOnly = new AssistantTool() {
        public String name() {
            return "owners_only";
        }

        public String title() {
            return "Owners only";
        }

        public String description() {
            return "Only for customers";
        }

        public Map<String, Object> parameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        public boolean availableTo(Caller caller) {
            return caller.ownsParcels();
        }

        public ToolResult run(JsonNode arguments, Caller caller) {
            return ToolResult.of(Map.of(), null);
        }
    };

    private final GeminiChatProvider provider = new GeminiChatProvider(
            request -> {
                requests.add(request.deepCopy());
                return json.readTree(answers.removeFirst());
            },
            new AssistantTools(List.of(echo, ownersOnly)),
            new AssistantProperties("gemini", 20, 2,
                    new AssistantProperties.Gemini("test-key", "test-model", "http://unused", Duration.ofSeconds(1))),
            json);

    private final Caller visitor = Caller.anonymous("127.0.0.1");

    @Test
    void runsTheToolsGeminiAsksForAndReturnsItsAnswer() {
        answers.add("""
                {"candidates":[{"content":{"role":"model","parts":[
                  {"functionCall":{"name":"echo_tool","args":{"word":"Kandy"}},"thoughtSignature":"abc"}]}}]}""");
        answers.add("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"It is Kandy."}]},
                  "finishReason":"STOP"}]}""");

        ChatReply reply = provider.reply(List.of(new Turn(true, "Say Kandy")), visitor);

        assertThat(reply.text()).isEqualTo("It is Kandy.");
        assertThat(reply.provider()).isEqualTo("gemini");
        assertThat(reply.cards()).containsExactly(new AssistantCard("TRACKING", Map.of("word", "Kandy")));
        assertThat(toolArguments).hasSize(1);

        JsonNode second = requests.get(1).path("contents");
        assertThat(second).hasSize(3);
        // Gemini's own turn goes back unchanged, thought signature included.
        assertThat(second.get(1).path("parts").get(0).path("thoughtSignature").asString("")).isEqualTo("abc");
        JsonNode response = second.get(2).path("parts").get(0).path("functionResponse");
        assertThat(second.get(2).path("role").asString("")).isEqualTo("user");
        assertThat(response.path("name").asString("")).isEqualTo("echo_tool");
        assertThat(response.path("response").path("echo").asString("")).isEqualTo("Kandy");
    }

    @Test
    void offersOnlyTheToolsTheCallerMayUse() {
        answers.add("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}");
        answers.add("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]}}]}");

        provider.reply(List.of(new Turn(true, "Hi")), visitor);
        provider.reply(List.of(new Turn(true, "Hi")), new Caller(UUID.randomUUID(), "CUSTOMER", "127.0.0.1"));

        assertThat(declaredTools(requests.get(0))).containsExactly("echo_tool");
        assertThat(declaredTools(requests.get(1))).containsExactly("echo_tool", "owners_only");
        // A tool without arguments is declared without a schema (Gemini rejects empty object schemas).
        assertThat(requests.get(1).at("/tools/0/functionDeclarations/1").has("parameters")).isFalse();
    }

    @Test
    void theConversationSentToGeminiStartsWithTheUser() {
        answers.add("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Sure\"}]}}]}");

        provider.reply(List.of(new Turn(false, "Hello! How can I help?"), new Turn(true, "Track my parcel")),
                visitor);

        JsonNode contents = requests.getFirst().path("contents");
        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).path("role").asString("")).isEqualTo("user");
        assertThat(requests.getFirst().at("/systemInstruction/parts/0/text").asString(""))
                .contains("SwiftDrop").doesNotContain("{today}");
    }

    @Test
    void aBlockedOrEmptyAnswerIsHandled() {
        answers.add("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertThat(provider.reply(List.of(new Turn(true, "bad")), visitor).text())
                .contains("only help with SwiftDrop deliveries");

        answers.add("{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"MAX_TOKENS\"}]}");
        assertThatThrownBy(() -> provider.reply(List.of(new Turn(true, "Hi")), visitor))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void stopsAfterTooManyToolRounds() {
        String call = """
                {"candidates":[{"content":{"parts":[{"functionCall":{"name":"echo_tool","args":{"word":"x"}}}]}}]}""";
        for (int i = 0; i < 5; i++) {
            answers.add(call);
        }

        assertThatThrownBy(() -> provider.reply(List.of(new Turn(true, "loop")), visitor))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(toolArguments).hasSize(3);
    }

    @Test
    void anUnknownToolBecomesAnErrorTheModelCanExplain() {
        answers.add("""
                {"candidates":[{"content":{"parts":[{"functionCall":{"name":"owners_only","args":{}}}]}}]}""");
        answers.add("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Please log in.\"}]}}]}");

        ChatReply reply = provider.reply(List.of(new Turn(true, "my parcels")), visitor);

        assertThat(reply.text()).isEqualTo("Please log in.");
        assertThat(requests.get(1).at("/contents/2/parts/0/functionResponse/response/error").asString(""))
                .contains("no tool called owners_only");
    }

    private static List<String> declaredTools(JsonNode request) {
        List<String> names = new ArrayList<>();
        request.at("/tools/0/functionDeclarations").forEach(node -> names.add(node.path("name").asString("")));
        return names;
    }
}
