package com.swiftdrop.merchants;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MerchantOrderTest extends IntegrationTest {

    private static final String ORDERS_PATH = "/api/v1/merchant/orders";
    private static final String KEYS_PATH = "/api/v1/merchant/api-keys";
    private static final String PAYMENT_METHOD_REQUIRED = "Choose how the delivery fee will be paid.";

    @Autowired
    JdbcTemplate jdbc;

    /** Registers a merchant, approves them and returns a logged-in cookie. */
    private Cookie approveAndLogin(String email, String phone) throws Exception {
        String userId = registerMerchant(email, phone);
        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());
        return tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
    }

    /** The user id of the merchant whose cookie this is; read from the DB by email. */
    private String merchantUserIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id::text FROM identity.users WHERE email = ?", String.class, email);
    }

    /** A valid merchant order body (COLOMBO → KANDY, 1.5 kg bike parcel, fee paid at pickup). */
    private static Map<String, Object> orderBody(String ref) {
        Map<String, Object> pickup = Map.of(
                "name", "Lanka Crafts",
                "phone", "0771234567",
                "addressLine", "12 High Level Road",
                "city", "Nugegoda",
                "district", "COLOMBO");
        Map<String, Object> recipient = Map.of(
                "name", "Nimal Perera",
                "phone", "0719876543",
                "addressLine", "45 Peradeniya Road",
                "city", "Kandy",
                "district", "KANDY");
        Map<String, Object> body = new LinkedHashMap<>();
        if (ref != null) {
            body.put("merchantOrderRef", ref);
        }
        body.put("pickup", pickup);
        body.put("recipient", recipient);
        body.put("weightKg", new BigDecimal("1.50"));
        body.put("lengthCm", 30);
        body.put("widthCm", 20);
        body.put("heightCm", 10);
        body.put("paymentMethod", "AT_PICKUP");
        return body;
    }

    // ---- Auth matrix ----

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(get(ORDERS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void customerGets403() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(ORDERS_PATH).cookie(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unapprovedMerchantCookieCannotCreateOrder() throws Exception {
        String email = uniqueEmail();
        Cookie merchant = approveAndLogin(email, uniquePhone());
        jdbc.update("UPDATE merchants.merchant_profiles SET approval_status = 'PENDING' WHERE user_id = ?::uuid",
                merchantUserIdByEmail(email));

        postJson(ORDERS_PATH, orderBody(null), merchant)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MERCHANT_NOT_APPROVED"));
    }

    // ---- Create ----

    @Test
    void approvedMerchantCreatesOrderWithCookieAuth() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        postJson(ORDERS_PATH, orderBody("ORD-1001"), merchant)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.trackingNumber").value(startsWith("SD")))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.merchantOrderRef").value("ORD-1001"))
                .andExpect(jsonPath("$.pickup.phone").value("+94771234567"))
                .andExpect(jsonPath("$.recipient.phone").value("+94719876543"))
                .andExpect(jsonPath("$.feeAmount").isNumber())
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.paymentMethod").value("AT_PICKUP"));
    }

    @Test
    void approvedMerchantCreatesOrderWithApiKeyAuth() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());
        String keyBody = postJson(KEYS_PATH, Map.of("name", "prod"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String rawKey = json.readTree(keyBody).get("rawKey").asString();

        mvc.perform(post(ORDERS_PATH)
                        .header("X-Api-Key", rawKey)
                        .contentType("application/json")
                        .content(json.writeValueAsString(orderBody("API-ORD-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.merchantOrderRef").value("API-ORD-1"));
    }

    @Test
    void sameMerchantOrderRefReturnsExistingParcel() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        String first = postJson(ORDERS_PATH, orderBody("IDEMPOTENT-1"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String firstId = json.readTree(first).get("id").asString();

        String second = postJson(ORDERS_PATH, orderBody("IDEMPOTENT-1"), merchant)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String secondId = json.readTree(second).get("id").asString();

        assert firstId.equals(secondId) : "resending the same merchantOrderRef must return the original parcel";
    }

    @Test
    void missingWeightReturns400() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());
        Map<String, Object> body = orderBody(null);
        body.remove("weightKg");

        postJson(ORDERS_PATH, body, merchant)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.weightKg").isNotEmpty());
    }

    @Test
    void missingMeasurementsReturns400() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());
        Map<String, Object> body = orderBody(null);
        body.remove("lengthCm");

        postJson(ORDERS_PATH, body, merchant)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.lengthCm").isNotEmpty());
    }

    // ---- Payment method ----

    @Test
    void theOrderStoresAndListsHowTheFeeIsPaid() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());
        Map<String, Object> body = orderBody("PAY-DROP-1");
        body.put("paymentMethod", "AT_DROP_OFF");

        String created = postJson(ORDERS_PATH, body, merchant)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod").value("AT_DROP_OFF"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asString();

        assertThat(jdbc.queryForObject("SELECT payment_method FROM delivery.parcels WHERE id = ?::uuid",
                String.class, id)).isEqualTo("AT_DROP_OFF");
        mvc.perform(get(ORDERS_PATH).cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')].paymentMethod", hasItem("AT_DROP_OFF")));
    }

    @Test
    void missingPaymentMethodReturns400() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());
        Map<String, Object> body = orderBody(null);
        body.remove("paymentMethod");

        postJson(ORDERS_PATH, body, merchant)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").value(PAYMENT_METHOD_REQUIRED));
    }

    @Test
    void unknownPaymentMethodReturns400() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());
        Map<String, Object> body = orderBody(null);
        body.put("paymentMethod", "CASH");

        postJson(ORDERS_PATH, body, merchant)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").value(PAYMENT_METHOD_REQUIRED));
    }

    // ---- List ----

    @Test
    void listReturnsOnlyOwnOrders() throws Exception {
        Cookie merchantA = approveAndLogin(uniqueEmail(), uniquePhone());
        Cookie merchantB = approveAndLogin(uniqueEmail(), uniquePhone());

        String bodyA = postJson(ORDERS_PATH, orderBody("A-REF-1"), merchantA)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String idA = json.readTree(bodyA).get("id").asString();

        String bodyB = postJson(ORDERS_PATH, orderBody("B-REF-1"), merchantB)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String idB = json.readTree(bodyB).get("id").asString();

        mvc.perform(get(ORDERS_PATH).cookie(merchantA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(idA)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(idB))));

        mvc.perform(get(ORDERS_PATH).cookie(merchantB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(idB)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(idA))));
    }

    @Test
    void listFilterByStatus() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());
        postJson(ORDERS_PATH, orderBody("FILTER-1"), merchant).andExpect(status().isCreated());

        mvc.perform(get(ORDERS_PATH).param("status", "CREATED").cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].merchantOrderRef", hasItem("FILTER-1")));

        mvc.perform(get(ORDERS_PATH).param("status", "DELIVERED").cookie(merchant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].merchantOrderRef", not(hasItem("FILTER-1"))));
    }

    @Test
    void badPageOrSizeReturns400() throws Exception {
        Cookie merchant = approveAndLogin(uniqueEmail(), uniquePhone());

        mvc.perform(get(ORDERS_PATH).param("page", "-1").cookie(merchant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.page").isNotEmpty());

        mvc.perform(get(ORDERS_PATH).param("size", "101").cookie(merchant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.size").isNotEmpty());
    }
}
