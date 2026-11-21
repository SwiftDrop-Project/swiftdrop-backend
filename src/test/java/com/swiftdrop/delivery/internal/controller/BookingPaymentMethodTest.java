package com.swiftdrop.delivery.internal.controller;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /deliveries with the payment method, against the real database (Testcontainers): it is required, stored in
 * delivery.parcels.payment_method and returned, and the table accepts only the three methods.
 */
class BookingPaymentMethodTest extends IntegrationTest {

    private static final String DELIVERIES = "/api/v1/deliveries";
    private static final String REQUIRED = "Choose how the delivery fee will be paid.";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theBookingStoresAndReturnsHowTheFeeIsPaid() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        String response = postJson(DELIVERIES, booking("AT_DROP_OFF"), customer)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod").value("AT_DROP_OFF"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).get("id").asString();

        mvc.perform(get(DELIVERIES + "/{id}", id).cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.paymentMethod").value("AT_DROP_OFF"));
        assertThat(jdbc.queryForObject("SELECT payment_method FROM delivery.parcels WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("AT_DROP_OFF");
    }

    @Test
    void aBookingWithoutAPaymentMethodIsRefused() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        Map<String, Object> body = booking("AT_PICKUP");
        body.remove("paymentMethod");

        postJson(DELIVERIES, body, customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").value(REQUIRED));
    }

    @Test
    void anUnknownPaymentMethodIsRefused() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        postJson(DELIVERIES, booking("CASH"), customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").value(REQUIRED));
    }

    @Test
    void theTableAcceptsOnlyTheThreeMethods() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String response = postJson(DELIVERIES, booking("ONLINE"), customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asString());

        assertThatThrownBy(() -> jdbc.update("UPDATE delivery.parcels SET payment_method = 'CASH' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT payment_method FROM delivery.parcels WHERE id = ?", String.class, id))
                .isEqualTo("ONLINE");
    }

    private static Map<String, Object> booking(String paymentMethod) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", Map.of("name", "Nimal Perera", "phone", uniquePhone(), "addressLine", "12 Galle Road",
                "city", "Colombo 03", "district", "COLOMBO"));
        body.put("recipient", Map.of("name", "Kamala Silva", "phone", uniquePhone(),
                "addressLine", "45 Peradeniya Road", "city", "Kandy", "district", "KANDY"));
        body.put("weightKg", new BigDecimal("1.50"));
        body.put("lengthCm", 30);
        body.put("widthCm", 20);
        body.put("heightCm", 10);
        body.put("paymentMethod", paymentMethod);
        return body;
    }
}
