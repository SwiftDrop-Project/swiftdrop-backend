package com.swiftdrop.merchants;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminMerchantTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(get("/api/v1/admin/merchants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerGets403() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get("/api/v1/admin/merchants").cookie(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", UUID.randomUUID()).cookie(customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListsPendingMerchants() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();
        String userId = registerMerchant(email, phone);

        mvc.perform(get("/api/v1/admin/merchants").param("status", "PENDING").cookie(loginAsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == '%s')].businessName", userId).value("Lanka Crafts"))
                .andExpect(jsonPath("$[?(@.userId == '%s')].contactPerson", userId).value("Nimal Perera"))
                .andExpect(jsonPath("$[?(@.userId == '%s')].email", userId).value(email))
                .andExpect(jsonPath("$[?(@.userId == '%s')].phone", userId).value(stored(phone)))
                .andExpect(jsonPath("$[?(@.userId == '%s')].pickupDistrict", userId).value("COLOMBO"))
                .andExpect(jsonPath("$[?(@.userId == '%s')].parcelsPerMonth", userId).value("FROM_50_TO_200"))
                .andExpect(jsonPath("$[?(@.userId == '%s')].createdAt", userId).isNotEmpty());
    }

    @Test
    void approveActivatesMerchantAndRecordsReviewer() throws Exception {
        String userId = registerMerchant(uniqueEmail(), uniquePhone());
        Cookie admin = loginAsAdmin();

        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("APPROVED"));

        mvc.perform(get("/api/v1/admin/merchants").cookie(admin))
                .andExpect(jsonPath("$[*].userId", not(hasItem(userId))));
        mvc.perform(get("/api/v1/admin/merchants").param("status", "APPROVED").cookie(admin))
                .andExpect(jsonPath("$[*].userId", hasItem(userId)));

        Map<String, Object> row = jdbc.queryForMap(
                "select reviewed_at, reviewed_by from merchants.merchant_profiles where user_id = ?", UUID.fromString(userId));
        assertThat(row.get("reviewed_at")).isNotNull();
        assertThat(row.get("reviewed_by")).isNotNull();
    }

    @Test
    void rejectMarksMerchantRejected() throws Exception {
        String userId = registerMerchant(uniqueEmail(), uniquePhone());

        mvc.perform(post("/api/v1/admin/merchants/{id}/reject", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("REJECTED"));
    }

    @Test
    void unknownMerchantReturns404() throws Exception {
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", UUID.randomUUID()).cookie(loginAsAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidStatusOrIdReturns400() throws Exception {
        Cookie admin = loginAsAdmin();
        mvc.perform(get("/api/v1/admin/merchants").param("status", "MAYBE").cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/admin/merchants/not-a-uuid/approve").cookie(admin))
                .andExpect(status().isBadRequest());
    }
}
