package com.swiftdrop.assistant.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swiftdrop.assistant.internal.tools.AssistantCard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gemini only rewrites the answer from SwiftDrop's data: stubbed Gemini, no network, no Spring. */
class GeminiAnswerWriterTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<JsonNode> requests = new ArrayList<>();
    private String answer;

    private final GeminiAnswerWriter writer = new GeminiAnswerWriter(request -> {
        requests.add(request.deepCopy());
        return json.readTree(answer);
    }, json);

    private final List<AssistantCard> price = List.of(new AssistantCard("PRICE", Map.of(
            "pickupDistrict", "Colombo", "dropOffDistrict", "Kandy", "weightKg", 2,
            "status", "COMPLETE", "total", new BigDecimal("472.60"), "totalText", "Rs. 472.60")));
    private final String draft = "Sending 2 kg from Colombo to Kandy costs Rs. 472.60.";

    private void geminiSays(String text) {
        answer = json.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "content", Map.of("role", "model", "parts", List.of(Map.of("text", text)))))));
    }

    @Test
    void geminiGetsTheQuestionTheDataAndTheDraftButNoTools() {
        geminiSays("It costs Rs. 472.60 to send your 2 kg parcel from Colombo to Kandy.");

        String text = writer.write("How much to Kandy?", draft, price);

        assertThat(text).isEqualTo("It costs Rs. 472.60 to send your 2 kg parcel from Colombo to Kandy.");
        JsonNode request = requests.getFirst();
        assertThat(request.has("tools")).isFalse();
        String prompt = request.at("/contents/0/parts/0/text").asString("");
        assertThat(prompt).contains("How much to Kandy?", "\"total\":472.60", draft);
        assertThat(request.at("/systemInstruction/parts/0/text").asString("")).contains("Use only the facts");
    }

    @Test
    void anAmountThatIsNotInTheDataIsRejected() {
        geminiSays("It costs about Rs. 500.00.");

        assertThatThrownBy(() -> writer.write("How much?", draft, price))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void aTrackingNumberThatIsNotInTheDataIsRejected() {
        List<AssistantCard> parcel = List.of(new AssistantCard("TRACKING",
                Map.of("trackingNumber", "SD2611874236", "statusText", "on its way")));
        geminiSays("SD 2611 8742 36 is on its way.");
        assertThat(writer.write("Where is it?", "SD 2611 8742 36 is on its way.", parcel))
                .isEqualTo("SD 2611 8742 36 is on its way.");

        geminiSays("SD 2611 0000 01 is on its way.");
        assertThatThrownBy(() -> writer.write("Where is it?", "SD 2611 8742 36 is on its way.", parcel))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void anyNumberThatIsNotInTheDataIsRejected() {
        List<AssistantCard> report = List.of(new AssistantCard("REPORT", Map.of(
                "booked", 1250, "delivered", 40, "failed", 2, "successRate", new BigDecimal("95.2"),
                "from", "2026-11-16")));
        String reportDraft = "Weekly report for 16 Nov 2026 to 22 Nov 2026: 1250 parcels booked.";

        geminiSays("This week 1,250 parcels were booked and 40 delivered, a 95.20% success rate.");
        assertThat(writer.write("Weekly report", reportDraft, report)).contains("1,250");

        // A total Gemini worked out itself (40 + 2) is not in the data.
        geminiSays("This week 1,250 parcels were booked and 42 deliveries finished.");
        assertThatThrownBy(() -> writer.write("Weekly report", reportDraft, report))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void noAnswerIsAnError() {
        answer = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";

        assertThatThrownBy(() -> writer.write("How much?", draft, price))
                .isInstanceOf(ProviderUnavailableException.class);
    }
}
