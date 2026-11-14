package com.swiftdrop.pricing;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin's Zones &amp; Pricing endpoints against the rate card and district centres the migrations create (in the
 * Testcontainers database, never Neon). Values that a later rate card may change are checked against each other,
 * not as fixed numbers.
 */
class AdminPricingTest extends IntegrationTest {

    private static final String BASE = "/api/v1/admin/pricing";

    @Test
    void needsALogin() throws Exception {
        mvc.perform(get(BASE + "/rate-card")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/rate-cards")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/districts")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/routes").param("from", "COLOMBO")).andExpect(status().isUnauthorized());
        postJson(BASE + "/estimate", estimateBody()).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsMayUseIt() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(BASE + "/rate-card").cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/rate-cards").cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/districts").cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/routes").param("from", "COLOMBO").cookie(customer))
                .andExpect(status().isForbidden());
        postJson(BASE + "/estimate", estimateBody(), customer).andExpect(status().isForbidden());
    }

    @Test
    void showsTheActiveRateCardWithItsVehicles() throws Exception {
        JsonNode card = json(mvc.perform(get(BASE + "/rate-card").cookie(loginAsAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(card.get("version").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(card.get("currency").asString()).isEqualTo("LKR");
        assertThat(card.get("vehicles").size()).isGreaterThan(0);
        assertThat(card.get("vehicles").get(0).get("vehicle").asString()).isEqualTo("BIKE");
    }

    @Test
    void listsEveryRateCardVersionNewestFirstWithExactlyOneActive() throws Exception {
        Cookie admin = loginAsAdmin();

        JsonNode cards = json(mvc.perform(get(BASE + "/rate-cards").cookie(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode active = json(mvc.perform(get(BASE + "/rate-card").cookie(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(cards.size()).isGreaterThanOrEqualTo(1);
        int activeCount = 0;
        int previousVersion = Integer.MAX_VALUE;
        for (JsonNode card : cards) {
            int version = card.get("version").asInt();
            assertThat(version).as("newest first").isLessThan(previousVersion);
            previousVersion = version;
            assertThat(card.get("vehicles").size()).as("version " + version + " has its vehicles").isGreaterThan(0);
            if (card.get("active").asBoolean()) {
                activeCount++;
                assertThat(version).isEqualTo(active.get("version").asInt());
            }
        }
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void listsAllTwentyFiveDistrictsWithTheirCentres() throws Exception {
        JsonNode districts = json(mvc.perform(get(BASE + "/districts").cookie(loginAsAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(districts).hasSize(25);
        JsonNode first = districts.get(0);
        assertThat(first.get("district").asString()).isEqualTo("AMPARA");
        assertThat(first.get("name").asString()).isEqualTo("Ampara");
        for (JsonNode district : districts) {
            assertThat(district.get("priced").asBoolean()).as(district.get("district").asString()).isTrue();
        }
    }

    @Test
    void theRouteTableMatchesTheQuoteForTheSameRoute() throws Exception {
        Cookie admin = loginAsAdmin();

        JsonNode table = json(mvc.perform(get(BASE + "/routes").param("from", "COLOMBO").cookie(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode quote = json(postJson(BASE + "/estimate", estimateBody(), admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(table.get("routes")).hasSize(25);
        JsonNode kandy = null;
        for (JsonNode route : table.get("routes")) {
            if (route.get("to").asString().equals("KANDY")) {
                kandy = route;
            }
            if (route.get("to").asString().equals("COLOMBO")) {
                assertThat(route.get("distanceKm").decimalValue()).isEqualByComparingTo("0");
            }
        }
        assertThat(kandy).isNotNull();
        assertThat(kandy.get("distanceKm").decimalValue()).isEqualByComparingTo(quote.get("distanceKm").decimalValue());
        BigDecimal quotedLinehaul = null;
        for (JsonNode line : quote.get("lines")) {
            if (line.get("component").asString().equals("LINEHAUL")) {
                quotedLinehaul = line.get("amount").decimalValue();
            }
        }
        assertThat(kandy.get("linehaul").decimalValue()).isEqualByComparingTo(quotedLinehaul);
        assertThat(table.get("rateCardVersion").asInt()).isEqualTo(quote.get("rateCardVersion").asInt());
    }

    @Test
    void theRouteTableNeedsAValidBranch() throws Exception {
        Cookie admin = loginAsAdmin();

        mvc.perform(get(BASE + "/routes").cookie(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(BASE + "/routes").param("from", "ATLANTIS").cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void theCalculatorValidatesLikeTheCustomerEstimate() throws Exception {
        Map<String, Object> body = estimateBody();
        body.put("weightKg", 75);

        postJson(BASE + "/estimate", body, loginAsAdmin())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.weightKg").value("A parcel can weigh up to 50 kg."));
    }

    private static Map<String, Object> estimateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickupDistrict", "COLOMBO");
        body.put("dropOffDistrict", "KANDY");
        body.put("weightKg", new BigDecimal("1.8"));
        body.put("lengthCm", 40);
        body.put("widthCm", 30);
        body.put("heightCm", 12);
        return body;
    }

    private JsonNode json(String content) {
        return json.readTree(content);
    }
}
