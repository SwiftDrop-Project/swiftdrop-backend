package com.swiftdrop.delivery.internal.controller;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /deliveries/{id}/receipt and /receipt/pdf with the security filters and the real database (Testcontainers):
 * no login → 401, another role → 403, someone else's delivery → 404, and the customer's own receipt as JSON and PDF.
 */
class ReceiptAccessTest extends IntegrationTest {

    private static final String DELIVERIES = "/api/v1/deliveries";

    @Test
    void withoutLoginBothReturn401() throws Exception {
        UUID id = UUID.randomUUID();
        for (String path : List.of(DELIVERIES + "/{id}/receipt", DELIVERIES + "/{id}/receipt/pdf")) {
            mvc.perform(get(path, id))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void anotherRoleGets403() throws Exception {
        UUID id = UUID.randomUUID();
        Cookie admin = loginAsAdmin();
        for (String path : List.of(DELIVERIES + "/{id}/receipt", DELIVERIES + "/{id}/receipt/pdf")) {
            mvc.perform(get(path, id).cookie(admin))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void theCustomerGetsTheirOwnReceiptAsJsonAndAsPdf() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String id = book(customer);

        mvc.perform(get(DELIVERIES + "/{id}/receipt", id).cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").isString())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.pickup.addressLine").value("12 Galle Road"))
                .andExpect(jsonPath("$.lines").isNotEmpty())
                .andExpect(jsonPath("$.lines[0].label").isString())
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.paymentMethod").value("AT_PICKUP"))
                .andExpect(jsonPath("$.paymentStatus").value("DUE"));

        byte[] pdf = mvc.perform(get(DELIVERIES + "/{id}/receipt/pdf", id).cookie(customer))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"SwiftDrop-receipt-SD")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void someoneElsesReceiptAndAnUnknownIdAreNotFoundAsJson() throws Exception {
        String theirs = book(registerCustomer(uniqueEmail(), uniquePhone()));
        Cookie me = registerCustomer(uniqueEmail(), uniquePhone());

        for (String id : List.of(theirs, UUID.randomUUID().toString())) {
            for (String path : List.of(DELIVERIES + "/{id}/receipt", DELIVERIES + "/{id}/receipt/pdf")) {
                mvc.perform(get(path, id).cookie(me))
                        .andExpect(status().isNotFound())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            }
        }
    }

    /** Books a delivery for the customer and returns its id. */
    private String book(Cookie customer) throws Exception {
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
        String response = postJson(DELIVERIES, body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asString();
    }
}
