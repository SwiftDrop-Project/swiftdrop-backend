package com.swiftdrop.assistant.internal.provider;

import com.swiftdrop.assistant.internal.tools.AssistantCard;
import com.swiftdrop.assistant.internal.tools.Words;
import com.swiftdrop.shared.reference.District;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Google Gemini as a writer only. SwiftDrop's own code has already understood the question and fetched the data from
 * its APIs (tracking, pricing); Gemini gets the question, that data and a draft answer made from it, and rewrites the
 * answer in clear, friendly sentences. It has no tools and nothing else to go on.
 *
 * <p>The answer is checked before it is used: every number in it (amounts, counts, percentages, dates), every
 * tracking number and every district must appear in the data or the draft, otherwise the draft is used instead
 * ({@link ProviderUnavailableException}).
 */
@Component
public class GeminiAnswerWriter {

    static final String NAME = "gemini";

    /** A number: 3, 1,250, 472.60 (a comma that ends it, as in "3, 4", is left out). */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])\\d+(?:,\\d{3})*(?:\\.\\d+)?");
    private static final Pattern TRACKING_NUMBER =
            Pattern.compile("\\bSD\\s*\\d{4}\\s*\\d{4}\\s*\\d{2}\\b", Pattern.CASE_INSENSITIVE);

    private final GeminiTransport transport;
    private final JsonMapper json;
    private final String instructions;

    public GeminiAnswerWriter(GeminiTransport transport, JsonMapper json) {
        this.transport = transport;
        this.json = json;
        this.instructions = load("assistant/answer-prompt.txt");
    }

    /**
     * @param question the user's latest message (earlier details are already in the draft)
     * @param draft    the answer SwiftDrop built from the data, true but plain
     * @param cards    the data from SwiftDrop's APIs the answer must be based on
     * @return the rewritten answer
     * @throws ProviderUnavailableException when Gemini can't be reached, gives no answer, or uses a fact that isn't
     *                                      in the data
     */
    public String write(String question, String draft, List<AssistantCard> cards) {
        String data = json.writeValueAsString(cards.stream().map(card -> card.data()).toList());

        ObjectNode request = json.createObjectNode();
        request.putObject("systemInstruction").putArray("parts").addObject().put("text", instructions);
        ObjectNode user = request.putArray("contents").addObject();
        user.put("role", "user");
        user.putArray("parts").addObject().put("text", """
                Customer's question:
                %s

                Data from SwiftDrop (the only facts you may use):
                %s

                Draft answer made from that data:
                %s""".formatted(question, data, draft));
        request.putObject("generationConfig").put("temperature", 0.2);

        JsonNode candidate = transport.generate(request).path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text") && !part.path("thought").asBoolean(false)) {
                text.append(part.get("text").asString(""));
            }
        }
        String answer = text.toString().strip();
        if (answer.isEmpty()) {
            throw new ProviderUnavailableException("Gemini gave no answer ("
                    + candidate.path("finishReason").asString("no candidate") + ")");
        }
        String facts = normalise(data + " " + draft);
        if (!onlyKnownNumbers(answer, data + " " + draft) || !onlyKnown(TRACKING_NUMBER, answer, facts)) {
            throw new ProviderUnavailableException("Gemini's answer used a number that isn't in the data");
        }
        if (!onlyKnownDistricts(answer, data + " " + draft)) {
            throw new ProviderUnavailableException("Gemini's answer named a district that isn't in the data");
        }
        return answer;
    }

    /** True when every district named in the answer is also named in the facts. */
    private static boolean onlyKnownDistricts(String answer, String facts) {
        java.util.Set<District> known = new java.util.HashSet<>();
        Words.districtsIn(facts).forEach(mention -> known.add(mention.district()));
        return Words.districtsIn(answer).stream().allMatch(mention -> known.contains(mention.district()));
    }

    /** True when every match of {@code pattern} in the answer also appears in the data. */
    private static boolean onlyKnown(Pattern pattern, String answer, String facts) {
        Matcher matcher = pattern.matcher(answer);
        while (matcher.find()) {
            if (!facts.contains(normalise(matcher.group()))) {
                return false;
            }
        }
        return true;
    }

    /** True when every number in the answer is also a number in the facts (472.60 matches 472.6, 1,250 matches 1250). */
    private static boolean onlyKnownNumbers(String answer, String facts) {
        java.util.Set<String> known = new java.util.HashSet<>();
        NUMBER.matcher(facts).results().forEach(m -> known.add(number(m.group())));
        return NUMBER.matcher(answer).results().allMatch(m -> known.contains(number(m.group())));
    }

    private static String number(String text) {
        String plain = text.replace(",", "");
        return plain.contains(".") ? plain.replaceAll("0+$", "").replaceAll("\\.$", "") : plain;
    }

    private static String normalise(String text) {
        return text.replaceAll("[\\s,]", "").toUpperCase(Locale.ROOT);
    }

    private static String load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + path, e);
        }
    }
}
