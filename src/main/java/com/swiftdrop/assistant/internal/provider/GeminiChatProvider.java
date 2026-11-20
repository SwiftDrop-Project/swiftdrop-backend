package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.config.AssistantProperties;
import com.swiftdrop.assistant.internal.tools.AssistantCard;
import com.swiftdrop.assistant.internal.tools.AssistantTool;
import com.swiftdrop.assistant.internal.tools.AssistantTools;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.assistant.internal.tools.ToolResult;
import com.swiftdrop.assistant.internal.tools.Words;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini with function calling. Gemini decides which tools to call; this class runs them (as the caller,
 * never as anyone the model names) and sends the results back until Gemini answers in words, at most
 * {@code maxToolCalls} rounds.
 */
@Component
public class GeminiChatProvider implements ChatProvider {

    static final String NAME = "gemini";
    private static final String SAFE_REFUSAL = "Sorry, I can only help with SwiftDrop deliveries.";

    private final GeminiTransport transport;
    private final AssistantTools tools;
    private final AssistantProperties properties;
    private final JsonMapper json;
    private final String promptTemplate;

    public GeminiChatProvider(GeminiTransport transport, AssistantTools tools, AssistantProperties properties,
                              JsonMapper json) {
        this.transport = transport;
        this.tools = tools;
        this.properties = properties;
        this.json = json;
        this.promptTemplate = load("assistant/system-prompt.txt");
    }

    @Override
    public ChatReply reply(List<Turn> conversation, Caller caller) {
        ObjectNode request = json.createObjectNode();
        request.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt(caller));
        ArrayNode contents = request.putArray("contents");
        boolean started = false;
        for (Turn turn : conversation) {
            // Gemini wants the conversation to start with the user (the UI may show a greeting first).
            started = started || turn.fromUser();
            if (started) {
                ObjectNode content = contents.addObject();
                content.put("role", turn.fromUser() ? "user" : "model");
                content.putArray("parts").addObject().put("text", turn.text());
            }
        }
        ArrayNode declarations = request.putArray("tools").addObject().putArray("functionDeclarations");
        for (AssistantTool tool : tools.availableTo(caller)) {
            ObjectNode declaration = declarations.addObject();
            declaration.put("name", tool.name());
            declaration.put("description", tool.description());
            Map<String, Object> parameters = tool.parameters();
            if (parameters.get("properties") instanceof Map<?, ?> props && !props.isEmpty()) {
                declaration.set("parameters", json.valueToTree(parameters));
            }
        }
        request.putObject("generationConfig").put("temperature", 0.2);

        List<AssistantCard> cards = new ArrayList<>();
        for (int round = 0; round <= properties.maxToolCalls(); round++) {
            JsonNode candidate = transport.generate(request).path("candidates").path(0);
            if (candidate.isMissingNode()) {
                return new ChatReply(SAFE_REFUSAL, cards, NAME);
            }
            JsonNode content = candidate.path("content");
            List<JsonNode> calls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content.path("parts")) {
                if (part.has("functionCall")) {
                    calls.add(part.get("functionCall"));
                } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    text.append(part.get("text").asString(""));
                }
            }
            if (calls.isEmpty()) {
                String answer = text.toString().trim();
                if (!answer.isEmpty()) {
                    return new ChatReply(answer, cards, NAME);
                }
                if ("SAFETY".equals(candidate.path("finishReason").asString(""))) {
                    return new ChatReply(SAFE_REFUSAL, cards, NAME);
                }
                throw new ProviderUnavailableException("Gemini gave an empty answer ("
                        + candidate.path("finishReason").asString("no reason") + ")");
            }

            // Send back Gemini's own turn unchanged (it may carry thought signatures), then the tool results.
            ObjectNode modelTurn = (ObjectNode) content.deepCopy();
            modelTurn.put("role", "model");
            contents.add(modelTurn);
            ObjectNode results = contents.addObject();
            results.put("role", "user");
            ArrayNode parts = results.putArray("parts");
            for (JsonNode call : calls) {
                String name = call.path("name").asString("");
                ToolResult result = tools.run(name, call.path("args"), caller);
                if (result.card() != null && !cards.contains(result.card())) {
                    cards.add(result.card());
                }
                ObjectNode response = parts.addObject().putObject("functionResponse");
                if (call.has("id")) {
                    response.put("id", call.get("id").asString(""));
                }
                response.put("name", name);
                response.set("response", json.valueToTree(result.data()));
            }
        }
        throw new ProviderUnavailableException("Gemini used more than " + properties.maxToolCalls() + " tool rounds");
    }

    private String systemPrompt(Caller caller) {
        String today = LocalDate.now(Words.SRI_LANKA).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy",
                Locale.ENGLISH));
        String myParcels = caller.ownsParcels()
                ? "- Show the logged-in user's own parcels with the my_parcels tool."
                : "- The user isn't logged in as a customer or merchant: for \"my parcels\" questions ask for the "
                        + "tracking number, or suggest logging in.";
        return promptTemplate.replace("{today}", today).replace("{myParcels}", myParcels);
    }

    private static String load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + path, e);
        }
    }
}
