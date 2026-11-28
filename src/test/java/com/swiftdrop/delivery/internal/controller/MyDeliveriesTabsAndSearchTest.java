package com.swiftdrop.delivery.internal.controller;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /deliveries with several statuses (the "Active", "Delivered" and "Failed" tabs) and a tracking number search,
 * against the real database (Testcontainers). Only the logged-in customer's parcels are ever listed.
 */
class MyDeliveriesTabsAndSearchTest extends IntegrationTest {

    private static final String DELIVERIES = "/api/v1/deliveries";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theTabsListOnlyTheCustomersParcelsInThoseStatuses() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        Booked active = book(customer);
        Booked delivered = book(customer);
        Booked failed = book(customer);
        setStatus(delivered, "DELIVERED");
        setStatus(failed, "FAILED");
        Booked someoneElses = book(registerCustomer(uniqueEmail(), uniquePhone()));
        setStatus(someoneElses, "DELIVERED");

        mvc.perform(get(DELIVERIES).cookie(customer)
                        .param("status", "CREATED", "PICKED_UP", "AT_BRANCH", "IN_TRANSIT", "OUT_FOR_DELIVERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(active.id()));
        mvc.perform(get(DELIVERIES).cookie(customer).param("status", "DELIVERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(delivered.id()));
        mvc.perform(get(DELIVERIES).cookie(customer).param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(failed.id()));
        mvc.perform(get(DELIVERIES).cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].id")
                        .value(containsInAnyOrder(active.id(), delivered.id(), failed.id())));
    }

    @Test
    void theSearchFindsATrackingNumberTypedWithSpacesInLowerCase() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        Booked wanted = book(customer);
        book(customer);
        String number = wanted.trackingNumber();
        String typed = ("sd " + number.substring(2, 6) + " " + number.substring(6)).toLowerCase();

        mvc.perform(get(DELIVERIES).cookie(customer).param("q", typed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].trackingNumber").value(number));
    }

    @Test
    void anotherCustomersTrackingNumberIsNotFound() throws Exception {
        Booked someoneElses = book(registerCustomer(uniqueEmail(), uniquePhone()));
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(DELIVERIES).cookie(customer).param("q", someoneElses.trackingNumber()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void aTooLongSearchOrAnUnknownStatusIsRefused() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(DELIVERIES).cookie(customer).param("q", "S".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.q").value("Use at most 30 characters."));
        mvc.perform(get(DELIVERIES).cookie(customer).param("status", "DELIVERED", "LOST"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutLoginItIs401AndForAnotherRoleItIs403() throws Exception {
        mvc.perform(get(DELIVERIES).param("status", "DELIVERED"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(DELIVERIES).cookie(loginAsAdmin()).param("status", "DELIVERED"))
                .andExpect(status().isForbidden());
    }

    private record Booked(String id, String trackingNumber) {
    }

    private Booked book(Cookie customer) throws Exception {
        String response = postJson(DELIVERIES, booking(), customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var created = json.readTree(response);
        return new Booked(created.get("id").asString(), created.get("trackingNumber").asString());
    }

    private void setStatus(Booked parcel, String status) {
        jdbc.update("UPDATE delivery.parcels SET status = ? WHERE id = ?", status, UUID.fromString(parcel.id()));
    }

    private static Map<String, Object> booking() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", Map.of("name", "Nimal Perera", "phone", uniquePhone(), "addressLine", "12 Galle Road",
                "city", "Colombo 03", "district", "COLOMBO"));
        body.put("recipient", Map.of("name", "Kamala Silva", "phone", uniquePhone(),
                "addressLine", "45 Peradeniya Road", "city", "Kandy", "district", "KANDY"));
        body.put("weightKg", new BigDecimal("1.50"));
        body.put("lengthCm", 30);
        body.put("widthCm", 20);
        body.put("heightCm", 10);
        body.put("paymentMethod", "AT_PICKUP");
        return body;
    }
}
