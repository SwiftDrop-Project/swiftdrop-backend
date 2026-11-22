package com.swiftdrop.assistant.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swiftdrop.assistant.internal.tools.AssistantCard;
import com.swiftdrop.assistant.internal.tools.AssistantTools;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.assistant.internal.tools.ToolResult;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

/**
 * The answers come only from SwiftDrop's data (tools mocked): a question no tool has data for gets the fixed
 * "not in our system" answer with no cards, so Gemini never writes it.
 */
class RulesChatProviderTest {

    private final AssistantTools tools = mock(AssistantTools.class);
    private final RulesChatProvider rules = new RulesChatProvider(tools);
    private final Caller visitor = Caller.anonymous("127.0.0.1");
    private final Caller customer = new Caller(UUID.randomUUID(), "CUSTOMER", "c");

    private ChatReply ask(Caller caller, String... messages) {
        List<Turn> conversation = new java.util.ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            conversation.add(new Turn(i % 2 == 0, messages[i]));
        }
        return rules.reply(conversation, caller);
    }

    @ParameterizedTest
    @ValueSource(strings = {"What is your refund policy?", "Who won the cricket match yesterday?",
            "Can you write me a poem?", "What are your opening hours on Poya day?", "Is DHL cheaper than you?"})
    void questionsWithoutDataGetTheFixedAnswerAndNoCards(String question) {
        ChatReply reply = ask(visitor, question);

        assertThat(reply.text()).startsWith(RulesChatProvider.NO_DATA).contains("support@swiftdrop.lk");
        assertThat(reply.cards()).isEmpty();
        verify(tools, never()).run(anyString(), any(), any());
    }

    @Test
    void aGreetingIsAnsweredWithWhatTheAssistantCanDo() {
        ChatReply reply = ask(customer, "Hello");

        assertThat(reply.text()).startsWith("Hello! ")
                .contains("list the districts we deliver to and show your parcels")
                .doesNotContain("don't have that information");
        assertThat(reply.cards()).isEmpty();
        assertThat(ask(visitor, "Hi").text()).contains("estimate a delivery price and list the districts")
                .doesNotContain("show your parcels");
    }

    @Test
    void aParcelThatIsNotFoundSaysSoAndHasNoCard() {
        when(tools.run(eq("track_parcel"), any(), any())).thenReturn(ToolResult.of(Map.of("found", false), null));

        ChatReply reply = ask(visitor, "Where is SD2611999999?");

        assertThat(reply.text()).contains("couldn't find a parcel with the number SD2611999999",
                "support@swiftdrop.lk");
        assertThat(reply.cards()).isEmpty();
    }

    @Test
    void aFoundParcelIsAnsweredFromItsData() {
        Map<String, Object> parcel = Map.of("found", true, "trackingNumber", "SD 2611 8742 36",
                "statusText", "on its way to Kandy", "from", "Colombo 03, Colombo", "to", "Peradeniya, Kandy",
                "lastUpdate", "22 Nov 2026, 10:15 AM");
        when(tools.run(eq("track_parcel"), any(), any()))
                .thenReturn(ToolResult.of(parcel, new AssistantCard("TRACKING", parcel)));

        ChatReply reply = ask(visitor, "Where is SD2611874236?");

        assertThat(reply.text()).isEqualTo("SD 2611 8742 36 is on its way to Kandy. It's going from Colombo 03, "
                + "Colombo to Peradeniya, Kandy. Last update: 22 Nov 2026, 10:15 AM.");
        assertThat(reply.cards()).extracting(AssistantCard::type).containsExactly("TRACKING");
    }

    @Test
    void aToolErrorIsPassedOnWithoutACard() {
        when(tools.run(eq("track_parcel"), any(), any()))
                .thenReturn(ToolResult.failed("Too many lookups. Try again in a minute."));

        ChatReply reply = ask(visitor, "Track SD2611874236");

        assertThat(reply.text()).isEqualTo("Too many lookups. Try again in a minute.");
        assertThat(reply.cards()).isEmpty();
    }

    @Test
    void thePriceIsBuiltFromTheRateCardData() {
        // The rate card example: 2.5 kg, 40 x 30 x 12 cm (2.88 kg volumetric, so 3 kg billed), Colombo to Kandy.
        when(tools.run(eq("estimate_price"), any(), any())).thenReturn(price("COMPLETE", "522.60", List.of(
                line("WEIGHT", "100.00"), line("PICKUP", "150.00"), line("DELIVERY", "150.00"),
                line("LINEHAUL", "122.60"))));

        ChatReply reply = ask(visitor, "How much to send 2.5 kg from Colombo to Kandy, 40 x 30 x 12 cm?");

        assertThat(reply.text()).isEqualTo("Sending 2.5 kg from Colombo to Kandy (40 x 30 x 12 cm) costs about "
                + "Rs. 522.60. It goes by bike: weight (billed as 3 kg) Rs. 100.00, pickup by bike Rs. 150.00, "
                + "delivery by bike Rs. 150.00 and transport between branches (122.6 km) Rs. 122.60.");
        assertThat(reply.cards()).extracting(AssistantCard::type).containsExactly("PRICE");

        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        verify(tools).run(eq("estimate_price"), args.capture(), any());
        assertThat(args.getValue().path("pickupDistrict").asString("")).isEqualTo("COLOMBO");
        assertThat(args.getValue().path("dropOffDistrict").asString("")).isEqualTo("KANDY");
        assertThat(args.getValue().path("weightKg").asString("")).isEqualTo("2.5");
        assertThat(args.getValue().path("lengthCm").asInt()).isEqualTo(40);
        assertThat(args.getValue().path("heightCm").asInt()).isEqualTo(12);
    }

    @Test
    void aManualQuotePointsToTheTeam() {
        when(tools.run(eq("estimate_price"), any(), any())).thenReturn(price("MANUAL_QUOTE", null,
                List.of(line("WEIGHT", "119950.00"))));

        ChatReply reply = ask(visitor, "How much to send 49 kg from Colombo to Kandy, 300 x 200 x 200 cm?");

        assertThat(reply.text()).contains("needs a manual quote", "support@swiftdrop.lk").doesNotContain("Rs.");
    }

    @Test
    void missingPriceDetailsAreAskedForInsteadOfGuessed() {
        ChatReply reply = ask(visitor, "How much to send a parcel to Kandy?");

        assertThat(reply.text()).startsWith(RulesChatProvider.ASK_PRICE_DETAILS)
                .contains("weight", "collect it from", "its size").doesNotContain("Rs.");
        assertThat(reply.cards()).isEmpty();
        verify(tools, never()).run(anyString(), any(), any());
    }

    @Test
    void aWeightOutsideTheLimitsIsNotPriced() {
        ChatReply reply = ask(visitor, "How much to send 80 kg from Colombo to Kandy, 30 x 20 x 10 cm?");

        assertThat(reply.text()).isEqualTo("SwiftDrop takes parcels from 0.01 kg to 50 kg.");
        verify(tools, never()).run(anyString(), any(), any());
    }

    @Test
    void myParcelsNeedALogin() {
        ChatReply reply = ask(visitor, "Where are my parcels?");

        assertThat(reply.text()).contains("log in");
        assertThat(reply.cards()).isEmpty();
        verify(tools, never()).run(anyString(), any(), any());
    }

    @Test
    void aCustomerWithoutParcelsIsToldSo() {
        when(tools.run(eq("my_parcels"), any(), any()))
                .thenReturn(ToolResult.of(Map.of("parcels", List.of()), new AssistantCard("PARCEL_LIST", Map.of())));

        ChatReply reply = ask(customer, "Show my parcels");

        assertThat(reply.text()).isEqualTo("You don't have any parcels yet.");
        assertThat(reply.cards()).isEmpty();
    }

    @Test
    void theDistrictsComeFromTheSystemsList() {
        ChatReply reply = ask(visitor, "Which districts do you deliver to?");

        assertThat(reply.text()).contains("all 25 districts", "Colombo", "Nuwara Eliya", "Jaffna");
    }

    private static ToolResult price(String status, String total, List<Map<String, Object>> lines) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pickupDistrict", "Colombo");
        data.put("dropOffDistrict", "Kandy");
        data.put("weightKg", new BigDecimal(status.equals("COMPLETE") ? "2.50" : "49.00"));
        data.put("status", status);
        data.put("total", total == null ? null : new BigDecimal(total));
        data.put("totalText", total == null ? null : "Rs. " + total);
        data.put("billableWeightKg", status.equals("COMPLETE") ? 3 : 2400);
        data.put("vehicle", status.equals("COMPLETE") ? "BIKE" : null);
        data.put("vehicleText", status.equals("COMPLETE") ? "bike" : null);
        data.put("sizeCm", status.equals("COMPLETE") ? "40 x 30 x 12" : "300 x 200 x 200");
        data.put("distanceKm", new BigDecimal("122.6"));
        data.put("lines", lines);
        return ToolResult.of(data, new AssistantCard("PRICE", data));
    }

    private static Map<String, Object> line(String component, String amount) {
        return Map.of("component", component, "amount", new BigDecimal(amount));
    }
}
