package com.swiftdrop.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.assistant.internal.application.AssistantRateLimiter;
import com.swiftdrop.tracking.internal.application.TrackingRateLimiter;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * The assistant's chat endpoints with the rules provider (tests never call Gemini): the tools give the same answers
 * as SwiftDrop's own APIs, customers only see their own parcels, and the input rules and limits hold.
 */
class AssistantChatTest extends IntegrationTest {

    private static final String CHAT = "/api/v1/assistant/chat";
    private static final String CHAT_AS_USER = "/api/v1/assistant/chat/me";

    @Autowired
    AssistantRateLimiter assistantLimiter;

    @Autowired
    TrackingRateLimiter trackingLimiter;

    private Cookie customer;

    @BeforeEach
    void setUp() throws Exception {
        assistantLimiter.reset();
        trackingLimiter.reset();
        customer = registerCustomer(uniqueEmail(), uniquePhone());
    }

    // ---- Tracking ----

    @Test
    void tracksAParcelWithoutPersonalData() throws Exception {
        String number = book(customer, "COLOMBO", "KANDY");
        String spaced = number.substring(0, 2) + " " + number.substring(2, 6) + " " + number.substring(6, 10) + " "
                + number.substring(10);

        String body = ask(CHAT, null, "Where is my parcel " + spaced.toLowerCase() + "?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("rules"))
                .andExpect(jsonPath("$.cards[0].type").value("TRACKING"))
                .andExpect(jsonPath("$.cards[0].data.trackingNumber").value(number))
                .andExpect(jsonPath("$.cards[0].data.status").value("CREATED"))
                .andExpect(jsonPath("$.cards[0].data.from").value("Colombo 03, Colombo"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).path("reply").asString("")).contains(spaced, "waiting for a courier");
        assertThat(body).doesNotContain("Ayesha", "Kamal", "0771234567", "Galle Road", "Kandy Road");
    }

    @Test
    void saysSoWhenNoParcelHasTheNumber() throws Exception {
        ask(CHAT, null, "Track SD2611999999")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("couldn't find")))
                .andExpect(jsonPath("$.cards.length()").value(0));
    }

    // ---- Price ----

    @Test
    void priceIsTheSameAsThePricingApi() throws Exception {
        String estimate = postJson("/api/v1/pricing/estimate", Map.of("pickupDistrict", "COLOMBO",
                "dropOffDistrict", "KANDY", "weightKg", 2, "lengthCm", 30, "widthCm", 20, "heightCm", 10), customer)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        BigDecimal expected = json.readTree(estimate).path("total").decimalValue();

        JsonNode answer = read(ask(CHAT, null, "How much to send 2 kg from Colombo to Kandy, 30 x 20 x 10 cm?")
                .andExpect(status().isOk()));

        JsonNode card = answer.path("cards").path(0);
        assertThat(card.path("type").asString("")).isEqualTo("PRICE");
        assertThat(card.path("data").path("status").asString("")).isEqualTo("COMPLETE");
        assertThat(card.path("data").path("total").decimalValue()).isEqualByComparingTo(expected);
        assertThat(answer.path("reply").asString("")).contains("Colombo", "Kandy", "Rs. ");
    }

    @Test
    void asksForWhatIsMissingAndUsesTheAnswer() throws Exception {
        String question = "How much to send 2 kg to Kandy?";
        String followUp = read(ask(CHAT, null, question)).path("reply").asString("");
        assertThat(followUp).contains("collect it from").doesNotContain("Rs.");

        JsonNode answer = read(ask(CHAT, null, question, followUp, "Colombo"));

        assertThat(answer.path("cards").path(0).path("data").path("pickupDistrict").asString(""))
                .isEqualTo("Colombo");
        assertThat(answer.path("cards").path(0).path("data").path("dropOffDistrict").asString(""))
                .isEqualTo("Kandy");
        // Without measurements the estimate is provisional, and the answer says so.
        assertThat(answer.path("cards").path(0).path("data").path("status").asString("")).isEqualTo("PROVISIONAL");
        assertThat(answer.path("reply").asString("")).contains("plus the pickup and delivery charges");
    }

    @Test
    void continuesAPriceQuestionThatGeminiStarted() throws Exception {
        // Gemini asked in its own words, then timed out on the answer, so the rules provider takes over.
        JsonNode answer = read(ask(CHAT, null, "How much to send 2 kg to Kandy?",
                "Which district are you sending the parcel from?", "From Colombo, it's 30 x 20 x 10 cm"));

        JsonNode price = answer.path("cards").path(0).path("data");
        assertThat(price.path("pickupDistrict").asString("")).isEqualTo("Colombo");
        assertThat(price.path("dropOffDistrict").asString("")).isEqualTo("Kandy");
        assertThat(price.path("status").asString("")).isEqualTo("COMPLETE");
    }

    @Test
    void refusesWeightsSwiftDropDoesNotCarry() throws Exception {
        ask(CHAT, null, "How much to send 80 kg from Colombo to Galle?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("50 kg")))
                .andExpect(jsonPath("$.cards.length()").value(0));
    }

    // ---- My parcels ----

    @Test
    void aCustomerSeesOnlyTheirOwnParcels() throws Exception {
        String mine = book(customer, "GALLE", "MATARA");
        Cookie someoneElse = registerCustomer(uniqueEmail(), uniquePhone());
        String theirs = book(someoneElse, "GALLE", "MATARA");

        String body = ask(CHAT_AS_USER, customer, "Where are my parcels?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards[0].type").value("PARCEL_LIST"))
                .andExpect(jsonPath("$.cards[0].data.parcels.length()").value(1))
                .andExpect(jsonPath("$.cards[0].data.parcels[0].trackingNumber").value(mine))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(theirs);
    }

    @Test
    void visitorsAreAskedForATrackingNumber() throws Exception {
        // The public chat ignores the login cookie, so "my parcels" there never lists anything.
        ask(CHAT, customer, "Where are my parcels?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("tracking number")))
                .andExpect(jsonPath("$.cards.length()").value(0));
    }

    @Test
    void theUserChatNeedsALogin() throws Exception {
        ask(CHAT_AS_USER, null, "Where are my parcels?")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void listsTheDistricts() throws Exception {
        ask(CHAT, null, "Which districts do you deliver to?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("25 districts"),
                        org.hamcrest.Matchers.containsString("Nuwara Eliya"))));
    }

    @Test
    void offTopicQuestionsGetTheHelpText() throws Exception {
        ask(CHAT, null, "Write me a poem about cats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(
                        org.hamcrest.Matchers.containsString("only help with SwiftDrop deliveries")));
    }

    // ---- Admin reports ----

    @Test
    void anAdminGetsAWeeklyReportFromTheApis() throws Exception {
        Cookie admin = loginAsAdmin();
        book(customer, "AMPARA", "BATTICALOA");
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Colombo")).toString();

        JsonNode answer = read(ask(CHAT_AS_USER, admin, "Give me this week's report"));

        JsonNode report = answer.path("cards").path(0);
        assertThat(report.path("type").asString("")).isEqualTo("REPORT");
        JsonNode data = report.path("data");
        assertThat(data.path("period").asString("")).isEqualTo("WEEKLY");
        assertThat(data.path("countedTo").asString("")).isEqualTo(today);
        assertThat(data.path("booked").asLong()).isPositive();
        assertThat(data.path("perDay").size()).isBetween(1, 7);
        assertThat(data.path("statusNow").size()).isEqualTo(7);
        assertThat(data.path("accountsNow").path("customers").asLong()).isPositive();
        assertThat(answer.path("reply").asString(""))
                .startsWith("Weekly report for ")
                .contains(data.path("booked").asLong() + " parcels booked", "Compared with");
    }

    @Test
    void anAdminIsAskedWhichReportAndTheAnswerGivesIt() throws Exception {
        Cookie admin = loginAsAdmin();

        String question = read(ask(CHAT_AS_USER, admin, "Can I get a report?")).path("reply").asString("");
        assertThat(question).isEqualTo("Which report would you like: daily, weekly or monthly?");

        JsonNode answer = read(ask(CHAT_AS_USER, admin, "Can I get a report?", question, "monthly"));
        assertThat(answer.path("cards").path(0).path("data").path("period").asString("")).isEqualTo("MONTHLY");

        JsonNode yesterday = read(ask(CHAT_AS_USER, admin, "Summary for yesterday"));
        assertThat(yesterday.path("cards").path(0).path("data").path("from").asString("")).isEqualTo(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Colombo")).minusDays(1).toString());
    }

    @Test
    void anAdminAsksAboutParcelsAndAccounts() throws Exception {
        Cookie admin = loginAsAdmin();
        String number = book(customer, "MULLAITIVU", "KILINOCHCHI");

        JsonNode waiting = read(ask(CHAT_AS_USER, admin, "How many parcels are waiting for pickup?"));
        assertThat(waiting.path("cards").path(0).path("type").asString("")).isEqualTo("FACTS");
        assertThat(waiting.path("reply").asString("")).contains("waiting for pickup right now");

        JsonNode found = read(ask(CHAT_AS_USER, admin, "Show parcels from Mullaitivu going to Kilinochchi"));
        JsonNode list = found.path("cards").path(0);
        assertThat(list.path("type").asString("")).isEqualTo("PARCEL_LIST");
        assertThat(list.path("data").path("parcels").toString()).contains(number);
        // The admin sees the public view only: no names, phone numbers or street addresses.
        assertThat(found.toString()).doesNotContain("Ayesha", "0771234567", "Galle Road");

        JsonNode couriers = read(ask(CHAT_AS_USER, admin, "How many customers do we have?"));
        assertThat(couriers.path("reply").asString("")).matches("SwiftDrop has \\d+ customer accounts?\\.");

        JsonNode delivered = read(ask(CHAT_AS_USER, admin, "How many parcels were booked today?"));
        assertThat(delivered.path("reply").asString("")).contains("booked for");
        assertThat(delivered.path("cards").path(0).path("data").path("facts").size()).isEqualTo(4);
    }

    @Test
    void onlyAdminsGetReports() throws Exception {
        ask(CHAT_AS_USER, customer, "How many couriers do we have?")
                .andExpect(jsonPath("$.cards.length()").value(0));
        ask(CHAT_AS_USER, customer, "Give me this week's report")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(0))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Weekly report"))));
        // The public chat ignores the admin's cookie.
        ask(CHAT, loginAsAdmin(), "Give me this week's report")
                .andExpect(jsonPath("$.cards.length()").value(0));
    }

    // ---- Input rules and limits ----

    @Test
    void rejectsBadConversations() throws Exception {
        postJson(CHAT, Map.of("messages", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson(CHAT, Map.of("messages", List.of(Map.of("role", "robot", "text", "hi"))))
                .andExpect(status().isBadRequest());
        postJson(CHAT, Map.of("messages", List.of(Map.of("role", "user", "text", " "))))
                .andExpect(status().isBadRequest());
        ask(CHAT, null, "x".repeat(501))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.messages").value("Keep your message under 500 characters."));
        ask(CHAT, null, "Hi", "Hello! How can I help?")
                .andExpect(status().isBadRequest());
        List<Map<String, String>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(Map.of("role", i % 2 == 0 ? "user" : "assistant", "text", "hi"));
        }
        postJson(CHAT, Map.of("messages", tooMany)).andExpect(status().isBadRequest());
    }

    @Test
    void messagesAreLimitedPerClient() throws Exception {
        for (int i = 0; i < 20; i++) {
            ask(CHAT, null, "Hello").andExpect(status().isOk());
        }
        ask(CHAT, null, "Hello")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    // ---- Helpers ----

    /** Posts the messages, alternating user / assistant, starting with the user. */
    private ResultActions ask(String path, Cookie cookie, String... texts) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            messages.add(Map.of("role", i % 2 == 0 ? "user" : "assistant", "text", texts[i]));
        }
        var request = post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("messages", messages)));
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mvc.perform(request);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Books a parcel as {@code owner} and returns its tracking number. */
    private String book(Cookie owner, String pickupDistrict, String recipientDistrict) throws Exception {
        Map<String, Object> pickup = new HashMap<>(Map.of(
                "name", "Ayesha Fernando",
                "phone", "0771234567",
                "addressLine", "10 Galle Road",
                "city", "Colombo 03",
                "district", pickupDistrict));
        Map<String, Object> recipient = Map.of(
                "name", "Kamal Perera",
                "phone", "0719876543",
                "addressLine", "25 Kandy Road",
                "city", "City",
                "district", recipientDistrict);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", pickup);
        body.put("recipient", recipient);
        body.put("weightKg", new BigDecimal("1.50"));
        body.put("lengthCm", 30);
        body.put("widthCm", 20);
        body.put("heightCm", 10);
        body.put("paymentMethod", "AT_PICKUP");
        String response = postJson("/api/v1/deliveries", body, owner)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("trackingNumber").asString("");
    }
}
