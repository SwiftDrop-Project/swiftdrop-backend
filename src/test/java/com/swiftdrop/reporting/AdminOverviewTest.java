package com.swiftdrop.reporting;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /admin/reports/overview. The test database is shared by all tests, so the counts are checked as differences
 * (before and after), never as fixed numbers.
 */
class AdminOverviewTest extends IntegrationTest {

    private static final String OVERVIEW = "/api/v1/admin/reports/overview";

    @Test
    void needsALogin() throws Exception {
        mvc.perform(get(OVERVIEW)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsMaySeeIt() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(OVERVIEW).cookie(customer)).andExpect(status().isForbidden());
    }

    @Test
    void showsSevenDaysOfParcelCountsAndTheNewestAccountsWithoutContactDetails() throws Exception {
        Cookie admin = loginAsAdmin();
        String merchantId = registerMerchant(uniqueEmail(), uniquePhone());

        JsonNode overview = overview(admin);

        JsonNode week = overview.get("last7Days");
        assertThat(week.get("perDay")).hasSize(7);
        assertThat(overview.get("today").get("date").asString())
                .isEqualTo(week.get("perDay").get(6).get("date").asString())
                .isEqualTo(week.get("to").asString());
        assertThat(week.get("successRate").isNumber()).isTrue();
        assertThat(overview.get("users").get("admins").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode newest = overview.get("recentUsers").get(0);
        assertThat(newest.get("id").asString()).isEqualTo(merchantId);
        assertThat(newest.get("role").asString()).isEqualTo("MERCHANT");
        assertThat(newest.has("email")).isFalse();
        assertThat(newest.has("phone")).isFalse();
        assertThat(overview.get("recentUsers").size()).isLessThanOrEqualTo(5);
    }

    @Test
    void countsNewAccountsPerRoleAndMerchantsWaitingForApproval() throws Exception {
        Cookie admin = loginAsAdmin();
        JsonNode before = overview(admin).get("users");

        registerCustomer(uniqueEmail(), uniquePhone());
        registerMerchant(uniqueEmail(), uniquePhone());

        JsonNode after = overview(admin).get("users");
        assertThat(count(after, "customers") - count(before, "customers")).isEqualTo(1);
        assertThat(count(after, "merchants") - count(before, "merchants")).isEqualTo(1);
        assertThat(count(after, "pendingMerchants") - count(before, "pendingMerchants")).isEqualTo(1);
        assertThat(count(after, "total") - count(before, "total")).isEqualTo(2);
        assertThat(count(after, "total")).isEqualTo(count(after, "customers") + count(after, "merchants")
                + count(after, "couriers") + count(after, "dispatchers") + count(after, "admins"));
    }

    @Test
    void removedAccountsAreNotCounted() throws Exception {
        Cookie admin = loginAsAdmin();
        JsonNode before = overview(admin).get("users");

        String courierId = createCourier(admin);
        assertThat(count(overview(admin).get("users"), "couriers") - count(before, "couriers")).isEqualTo(1);

        mvc.perform(delete("/api/v1/admin/couriers/{id}", courierId).cookie(admin)).andExpect(status().isOk());

        JsonNode after = overview(admin);
        assertThat(count(after.get("users"), "couriers")).isEqualTo(count(before, "couriers"));
        assertThat(count(after.get("users"), "total")).isEqualTo(count(before, "total"));
        after.get("recentUsers").forEach(user -> assertThat(user.get("id").asString()).isNotEqualTo(courierId));
    }

    private JsonNode overview(Cookie admin) throws Exception {
        String body = mvc.perform(get(OVERVIEW).cookie(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static long count(JsonNode users, String field) {
        return users.get(field).asLong();
    }

    private String createCourier(Cookie admin) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "Kasun Jayasinghe");
        body.put("email", uniqueEmail());
        body.put("phone", uniquePhone());
        body.put("branch", "COLOMBO");
        body.put("vehicleType", "MOTORBIKE");
        body.put("password", "InitCourier9");
        String created = postJson("/api/v1/admin/couriers", body, admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(created).get("id").asString();
    }
}
