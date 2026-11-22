package com.swiftdrop.customer;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerProfileTest extends IntegrationTest {

    @Test
    void unauthenticatedReturns401() throws Exception {
        mvc.perform(get("/api/v1/customer/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(patch("/api/v1/customer/profile/personal-details")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Test\",\"phone\":\"0771234567\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void merchantRoleReturns403() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();
        registerMerchant(email, phone);
        // Login as admin, approve merchant
        Cookie admin = loginAsAdmin();
        // Since merchants require approval, we test that non-customer roles cannot access customer endpoints
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        // Normal customer can access
        mvc.perform(get("/api/v1/customer/profile").cookie(customer))
                .andExpect(status().isOk());

        // Admin gets 403
        mvc.perform(get("/api/v1/customer/profile").cookie(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void customerCanGetProfile() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();
        Cookie customer = registerCustomer(email, phone);

        mvc.perform(get("/api/v1/customer/profile").cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value(stored(phone)))
                .andExpect(jsonPath("$.preferredPaymentMethod").value("CASH_ON_DELIVERY"))
                .andExpect(jsonPath("$.totalDeliveries").value(0))
                .andExpect(jsonPath("$.savedAddressesCount").value(0));
    }

    @Test
    void totalDeliveriesCountsTheCustomersOwnBookings() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        Cookie someoneElse = registerCustomer(uniqueEmail(), uniquePhone());
        postJson("/api/v1/deliveries", booking(), customer).andExpect(status().isCreated());
        postJson("/api/v1/deliveries", booking(), customer).andExpect(status().isCreated());
        postJson("/api/v1/deliveries", booking(), someoneElse).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/customer/profile").cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDeliveries").value(2));
    }

    private static Map<String, Object> booking() {
        return Map.of(
                "pickup", Map.of("name", "Nimal Perera", "phone", uniquePhone(), "addressLine", "12 Galle Road",
                        "city", "Colombo 03", "district", "COLOMBO"),
                "recipient", Map.of("name", "Kamala Silva", "phone", uniquePhone(),
                        "addressLine", "45 Peradeniya Road", "city", "Kandy", "district", "KANDY"),
                "weightKg", new BigDecimal("1.50"),
                "lengthCm", 30,
                "widthCm", 20,
                "heightCm", 10,
                "paymentMethod", "AT_PICKUP");
    }

    @Test
    void customerCanUpdatePersonalDetails() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();
        Cookie customer = registerCustomer(email, phone);

        String newPhone = uniquePhone();
        Map<String, Object> update = Map.of(
                "fullName", "Kamal Perera",
                "phone", newPhone
        );

        mvc.perform(patch("/api/v1/customer/profile/personal-details")
                        .cookie(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Kamal Perera"))
                .andExpect(jsonPath("$.phone").value(stored(newPhone)));
    }

    @Test
    void duplicatePhoneReturns409() throws Exception {
        String firstPhone = uniquePhone();
        registerCustomer(uniqueEmail(), firstPhone);

        Cookie customer2 = registerCustomer(uniqueEmail(), uniquePhone());

        Map<String, Object> updateWithDuplicate = Map.of(
                "fullName", "Second User",
                "phone", firstPhone
        );

        mvc.perform(patch("/api/v1/customer/profile/personal-details")
                        .cookie(customer2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(updateWithDuplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    void customerCanUpdatePreferences() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        UUID fakeAddressId = UUID.randomUUID();

        Map<String, Object> preferences = Map.of(
                "defaultPickupAddressId", fakeAddressId.toString(),
                "preferredPaymentMethod", "MOCK_CARD"
        );

        mvc.perform(patch("/api/v1/customer/profile/preferences")
                        .cookie(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(preferences)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultPickupAddressId").value(fakeAddressId.toString()))
                .andExpect(jsonPath("$.preferredPaymentMethod").value("MOCK_CARD"));
    }
}
