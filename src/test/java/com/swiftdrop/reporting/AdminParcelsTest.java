package com.swiftdrop.reporting;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /admin/parcels. The test database is shared by all tests, so each test books its own parcels with a unique
 * recipient name and finds them by searching for it; counts are checked as differences.
 */
class AdminParcelsTest extends IntegrationTest {

    private static final String PARCELS = "/api/v1/admin/parcels";

    // ---- Access ----

    @Test
    void everyEndpointNeedsAnAdmin() throws Exception {
        mvc.perform(get(PARCELS)).andExpect(status().isUnauthorized());
        mvc.perform(get(PARCELS + "/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get(PARCELS + "/export")).andExpect(status().isUnauthorized());

        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        mvc.perform(get(PARCELS).cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(PARCELS + "/stats").cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(PARCELS + "/{id}", UUID.randomUUID()).cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(PARCELS + "/export").cookie(customer)).andExpect(status().isForbidden());
    }

    @Test
    void adminsCannotAssignOrReassignCouriersHere() throws Exception {
        Cookie admin = loginAsAdmin();
        String id = book(registerCustomer(uniqueEmail(), uniquePhone()), uniqueName(), uniquePhone(), "KANDY", null)
                .get("id").asString();
        Map<String, Object> body = Map.of("courierId", UUID.randomUUID().toString());

        for (String path : new String[] {"/assign", "/assign-courier", "/reassign"}) {
            int post = postJson(PARCELS + "/" + id + path, body, admin).andReturn().getResponse().getStatus();
            int patch = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .patch(PARCELS + "/" + id + path).cookie(admin)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andReturn().getResponse().getStatus();
            assertThat(post).as("POST " + path).isIn(404, 405);
            assertThat(patch).as("PATCH " + path).isIn(404, 405);
        }
        // the parcel is untouched
        mvc.perform(get(PARCELS + "/{id}", id).cookie(admin))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.history.length()").value(1));
    }

    // ---- List, search and filters ----

    @Test
    void findsAParcelByTrackingNumberNamePhoneOrId() throws Exception {
        Cookie admin = loginAsAdmin();
        String receiver = uniqueName();
        String receiverPhone = uniquePhone();
        JsonNode booked = book(registerCustomer(uniqueEmail(), uniquePhone()), receiver, receiverPhone, "KANDY", null);
        String id = booked.get("id").asString();
        String tracking = booked.get("trackingNumber").asString();

        // tracking number with spaces and lower case, as people type it
        String spaced = tracking.substring(0, 2).toLowerCase() + " " + tracking.substring(2, 6) + " "
                + tracking.substring(6);
        mvc.perform(get(PARCELS).param("q", spaced).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].trackingNumber").value(tracking))
                .andExpect(jsonPath("$.content[0].status").value("CREATED"))
                .andExpect(jsonPath("$.content[0].senderType").value("CUSTOMER"))
                .andExpect(jsonPath("$.content[0].senderName").value("Nimal Perera"))
                .andExpect(jsonPath("$.content[0].receiverName").value(receiver))
                .andExpect(jsonPath("$.content[0].deliveryDistrict").value("KANDY"))
                .andExpect(jsonPath("$.content[0].feeAmount").exists())
                // no phones or street addresses in the list
                .andExpect(jsonPath("$.content[0].receiverPhone").doesNotExist())
                .andExpect(jsonPath("$.content[0].recipient").doesNotExist());

        mvc.perform(get(PARCELS).param("q", receiver.toLowerCase()).cookie(admin))
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));
        mvc.perform(get(PARCELS).param("q", receiverPhone).cookie(admin))
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));
        mvc.perform(get(PARCELS).param("q", id).cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void filtersByStatusDistrictDatePaymentAndRejectsBadFilters() throws Exception {
        Cookie admin = loginAsAdmin();
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String receiver = uniqueName();
        String paid = book(customer, receiver, uniquePhone(), "KANDY", null).get("id").asString();
        String cod = book(customer, receiver, uniquePhone(), "GALLE", new BigDecimal("2500.00")).get("id").asString();

        mvc.perform(get(PARCELS).param("q", receiver).param("status", "CREATED").param("status", "PICKED_UP")
                        .cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(PARCELS).param("q", receiver).param("status", "DELIVERED").cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(PARCELS).param("q", receiver).param("deliveryDistrict", "GALLE").cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(cod));
        mvc.perform(get(PARCELS).param("q", receiver).param("pickupDistrict", "COLOMBO").cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(PARCELS).param("q", receiver).param("payment", "CASH_ON_DELIVERY").cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(cod))
                .andExpect(jsonPath("$.content[0].codAmount").value(2500.00));
        mvc.perform(get(PARCELS).param("q", receiver).param("payment", "PREPAID").cookie(admin))
                .andExpect(jsonPath("$.content[*].id", hasItem(paid)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(cod))));
        mvc.perform(get(PARCELS).param("q", receiver).param("createdTo", "2020-01-01").cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(PARCELS).param("q", receiver).param("createdFrom", "2020-01-01").param("sort", "OLDEST")
                        .cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(paid));

        mvc.perform(get(PARCELS).param("createdFrom", "2026-11-10").param("createdTo", "2026-11-01").cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(PARCELS).param("status", "CANCELLED").cookie(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(PARCELS).param("sort", "RANDOM").cookie(admin)).andExpect(status().isBadRequest());
    }

    // ---- Details and tracking ----

    @Test
    void showsTheParcelWithItsSenderAccountAndHistory() throws Exception {
        Cookie admin = loginAsAdmin();
        String receiver = uniqueName();
        String receiverPhone = uniquePhone();
        String id = book(registerCustomer(uniqueEmail(), uniquePhone()), receiver, receiverPhone, "KANDY", null)
                .get("id").asString();

        mvc.perform(get(PARCELS + "/{id}", id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.sender.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.sender.accountName").value("Ayesha Fernando"))
                .andExpect(jsonPath("$.sender.businessName").isEmpty())
                .andExpect(jsonPath("$.pickup.district").value("COLOMBO"))
                .andExpect(jsonPath("$.recipient.name").value(receiver))
                .andExpect(jsonPath("$.recipient.phone").value(stored(receiverPhone)))
                .andExpect(jsonPath("$.instructions").value("Call before coming"))
                .andExpect(jsonPath("$.history[0].status").value("CREATED"))
                .andExpect(jsonPath("$.history[0].occurredAt").exists());

        mvc.perform(get(PARCELS + "/{id}/tracking", id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CREATED"));

        mvc.perform(get(PARCELS + "/{id}", UUID.randomUUID()).cookie(admin)).andExpect(status().isNotFound());
        mvc.perform(get(PARCELS + "/{id}/tracking", UUID.randomUUID()).cookie(admin))
                .andExpect(status().isNotFound());
    }

    // ---- Stats ----

    @Test
    void statsCountNewParcelsAsPending() throws Exception {
        Cookie admin = loginAsAdmin();
        JsonNode before = json(mvc.perform(get(PARCELS + "/stats").cookie(admin)).andExpect(status().isOk())
                .andReturn());

        book(registerCustomer(uniqueEmail(), uniquePhone()), uniqueName(), uniquePhone(), "KANDY", null);

        JsonNode after = json(mvc.perform(get(PARCELS + "/stats").cookie(admin)).andReturn());
        assertThat(after.get("total").asLong() - before.get("total").asLong()).isEqualTo(1);
        assertThat(after.get("pending").asLong() - before.get("pending").asLong()).isEqualTo(1);
        assertThat(after.get("byStatus").get("CREATED").asLong()).isEqualTo(after.get("pending").asLong());
        assertThat(after.get("total").asLong()).isEqualTo(after.get("pending").asLong()
                + after.get("inTransit").asLong() + after.get("delivered").asLong() + after.get("failed").asLong());
    }

    // ---- Export ----

    @Test
    void exportsTheFilteredParcelsAsSafeCsv() throws Exception {
        Cookie admin = loginAsAdmin();
        String receiver = "=HYPERLINK(" + uniqueName() + ")";
        String tracking = book(registerCustomer(uniqueEmail(), uniquePhone()), receiver, uniquePhone(), "KANDY", null)
                .get("trackingNumber").asString();

        MvcResult result = mvc.perform(get(PARCELS + "/export").param("q", tracking).cookie(admin))
                .andExpect(status().isOk())
                .andReturn();
        String csv = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment").contains("swiftdrop-parcels-");
        assertThat(csv.lines()).hasSize(2);
        assertThat(csv).startsWith("Tracking number,Parcel ID,Status,").contains(tracking).contains("'=HYPERLINK(");
    }

    // ---- Helpers ----

    /** Books a parcel from Colombo as the customer; the recipient name makes it findable. */
    private JsonNode book(Cookie customer, String receiverName, String receiverPhone, String district,
                          BigDecimal codAmount) throws Exception {
        Map<String, Object> pickup = Map.of("name", "Nimal Perera", "phone", uniquePhone(),
                "addressLine", "12 Galle Road", "city", "Colombo 05", "district", "COLOMBO");
        Map<String, Object> recipient = Map.of("name", receiverName, "phone", receiverPhone,
                "addressLine", "45 Main Street", "city", "Town", "district", district);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickup", pickup);
        body.put("recipient", recipient);
        body.put("weightKg", new BigDecimal("2.50"));
        if (codAmount != null) {
            body.put("codAmount", codAmount);
        }
        body.put("instructions", "Call before coming");
        return json(postJson("/api/v1/deliveries", body, customer).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String uniqueName() {
        return "Receiver " + UUID.randomUUID().toString().substring(0, 8);
    }
}
