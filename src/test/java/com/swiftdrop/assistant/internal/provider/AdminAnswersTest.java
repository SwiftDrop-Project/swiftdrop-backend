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
import com.swiftdrop.assistant.internal.tools.Words;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

/** Which admin tool each kind of question uses, and the answer built from its data (tools mocked). */
class AdminAnswersTest {

    private final AssistantTools tools = mock(AssistantTools.class);
    private final AdminAnswers answers = new AdminAnswers(tools);
    private final Caller admin = new Caller(UUID.randomUUID(), "ADMIN", "a");
    private final List<AssistantCard> cards = new ArrayList<>();
    private final LocalDate today = LocalDate.now(Words.SRI_LANKA);

    @BeforeEach
    void setUp() {
        List<Map<String, Object>> counts = List.of(
                Map.of("status", "CREATED", "label", "Waiting for pickup", "count", 5L),
                Map.of("status", "OUT_FOR_DELIVERY", "label", "Out for delivery", "count", 3L));
        when(tools.run(eq("parcel_counts"), any(), any())).thenReturn(ToolResult.of(
                Map.of("counts", counts, "total", 8L), AssistantCard.facts("Parcels by status now", null, List.of())));
        when(tools.run(eq("account_counts"), any(), any())).thenReturn(ToolResult.of(Map.of(
                "total", 30L, "customers", 20L, "merchants", 5L, "couriers", 3L, "dispatchers", 1L, "admins", 1L,
                "pendingMerchants", 2L), AssistantCard.facts("Accounts", null, List.of())));
        when(tools.run(eq("find_parcels"), any(), any())).thenReturn(ToolResult.of(Map.of(
                "total", 1L, "shown", 1, "filters", "failed delivery, going to Kandy",
                "parcels", List.of(Map.of("trackingNumber", "SD2611874236", "to", "City, Kandy",
                        "statusText", "not delivered"))), new AssistantCard("PARCEL_LIST", Map.of())));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("rangeText", "19 Nov 2026");
        report.put("booked", 12L);
        report.put("delivered", 8L);
        report.put("failed", 4L);
        report.put("successRate", new BigDecimal("66.7"));
        report.put("previous", Map.of("text", "the day before (18 Nov 2026)", "successRate", new BigDecimal("80.0")));
        report.put("changes", Map.of(
                "booked", Map.of("difference", 4L, "percent", new BigDecimal("50.0")),
                "delivered", Map.of("difference", 0L),
                "failed", Map.of("difference", -1L, "percent", new BigDecimal("-20.0"))));
        report.put("busiestDay", Map.of("dateText", "Mon 16 Nov", "booked", 5L));
        report.put("topPickupDistricts", List.of(Map.of("district", "Colombo", "count", 9L)));
        report.put("topDropOffDistricts", List.of(Map.of("district", "Kandy", "count", 4L)));
        report.put("failureReasons", List.of(Map.of("reason", "the address was wrong", "count", 2L)));
        when(tools.run(eq("service_report"), any(), any())).thenReturn(ToolResult.of(report,
                new AssistantCard("REPORT", report)));
    }

    private JsonNode argumentsOf(String tool) {
        ArgumentCaptor<JsonNode> arguments = ArgumentCaptor.forClass(JsonNode.class);
        verify(tools).run(eq(tool), arguments.capture(), any());
        return arguments.getValue();
    }

    @Test
    void parcelsInAStatusNow() {
        assertThat(answers.answer("How many parcels are out for delivery?", admin, cards))
                .isEqualTo("3 parcels are out for delivery right now, out of 8 parcels in all.");
        assertThat(answers.answer("How many parcels are waiting to be picked up", admin, cards))
                .startsWith("5 parcels are waiting for pickup");
        assertThat(answers.answer("How many parcels do we have?", admin, cards))
                .startsWith("Right now there are 8 parcels in all: 5 waiting for pickup");
        assertThat(cards).extracting(AssistantCard::type).containsOnly("FACTS");
    }

    @Test
    void accounts() {
        assertThat(answers.answer("How many couriers do we have?", admin, cards))
                .isEqualTo("SwiftDrop has 3 courier accounts.");
        assertThat(answers.answer("Are any merchants waiting for approval?", admin, cards))
                .isEqualTo("2 merchants are waiting for approval, out of 5 merchant accounts.");
        assertThat(answers.answer("How many users are there?", admin, cards)).startsWith("SwiftDrop has 30 accounts");
        verify(tools, never()).run(eq("parcel_counts"), any(), any());
    }

    @Test
    void figuresOfAPeriodComeFromTheReport() {
        assertThat(answers.answer("How many parcels were booked today?", admin, cards))
                .isEqualTo("12 parcels were booked for 19 Nov 2026. That's up 4 (50%) compared with the day before "
                        + "(18 Nov 2026).");
        assertThat(argumentsOf("service_report").path("period").asString("")).isEqualTo("DAILY");
        assertThat(cards.getFirst().type()).isEqualTo("FACTS");

        assertThat(answers.answer("What is the success rate this month?", admin, cards))
                .startsWith("The delivery success rate for 19 Nov 2026 was 66.7%: 8 delivered and 4 failed.");
        assertThat(answers.answer("Which was the busiest day this week?", admin, cards))
                .contains("Mon 16 Nov", "5 parcels booked");
        assertThat(answers.answer("Top districts last month", admin, cards))
                .contains("collected in Colombo (9)", "going to Kandy (4)");
        assertThat(answers.answer("Why did deliveries fail yesterday?", admin, cards))
                .contains("the address was wrong (2)");
        assertThat(answers.answer("Deliveries delivered yesterday", admin, cards))
                .startsWith("8 parcels were delivered").contains("same as the day before");
    }

    @Test
    void findsParcelsByStatusDistrictBranchAndDay() {
        assertThat(answers.answer("Show failed parcels going to Kandy", admin, cards))
                .startsWith("1 parcel matches (failed delivery, going to Kandy).")
                .contains("SD 2611 8742 36");
        JsonNode search = argumentsOf("find_parcels");
        assertThat(search.path("status").asString("")).isEqualTo("FAILED");
        assertThat(search.path("dropOffDistrict").asString("")).isEqualTo("KANDY");
        assertThat(cards).extracting(AssistantCard::type).containsExactly("PARCEL_LIST");
    }

    @Test
    void branchAndPickupDistrictsAndDays() {
        answers.answer("List parcels at the Colombo branch booked today", admin, cards);
        JsonNode search = argumentsOf("find_parcels");
        assertThat(search.path("branch").asString("")).isEqualTo("COLOMBO");
        assertThat(search.path("bookedFrom").asString("")).isEqualTo(today.toString());
        assertThat(search.path("bookedTo").asString("")).isEqualTo(today.toString());
    }

    @Test
    void parcelsFromADistrict() {
        answers.answer("How many parcels from Galle are in transit?", admin, cards);
        JsonNode search = argumentsOf("find_parcels");
        assertThat(search.path("pickupDistrict").asString("")).isEqualTo("GALLE");
        assertThat(search.path("status").asString("")).isEqualTo("IN_TRANSIT");
    }

    @Test
    void otherQuestionsAreLeftToTheUsualAnswers() {
        assertThat(answers.answer("How much to send 2 kg from Colombo to Kandy?", admin, cards)).isNull();
        assertThat(answers.answer("Which districts do you deliver to?", admin, cards)).isNull();
        assertThat(answers.answer("Hello", admin, cards)).isNull();
        verify(tools, never()).run(anyString(), any(), any());
    }

    @Test
    void priceQuestionsAreLeftToThePriceEstimate() {
        assertThat(answers.answer("What is the delivery rate from Colombo to Kandy for 2 kg?", admin, cards))
                .isNull();
        assertThat(answers.answer("Delivery rate to Kandy", admin, cards)).isNull();
        assertThat(answers.answer("What do 3kg parcels sent to Galle cost?", admin, cards)).isNull();
        verify(tools, never()).run(anyString(), any(), any());

        assertThat(answers.answer("What is the delivery rate this month?", admin, cards))
                .startsWith("The delivery success rate");
    }
}
