package com.swiftdrop.customer;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /customer/addresses against the real database (Testcontainers): saving, listing, editing, the default pickup
 * address, deleting, the limit of 20, the validation rules and that nobody can touch another customer's address.
 */
class CustomerAddressTest extends IntegrationTest {

    private static final String ADDRESSES = "/api/v1/customer/addresses";

    @Autowired
    JdbcTemplate jdbc;

    // ---- 401 / 403 ----

    @Test
    void withoutLoginItIs401AndForAnotherRoleItIs403() throws Exception {
        mvc.perform(get(ADDRESSES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        postJson(ADDRESSES, address("Home", false))
                .andExpect(status().isUnauthorized());

        Cookie admin = loginAsAdmin();
        mvc.perform(get(ADDRESSES).cookie(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        postJson(ADDRESSES, address("Home", false), admin)
                .andExpect(status().isForbidden());
    }

    // ---- save and list ----

    @Test
    void aSavedAddressIsStoredWithTheNormalisedPhoneAndListed() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        Map<String, Object> body = address("  Home  ", false);
        body.put("phone", "077 123 4567");
        body.put("landmark", "  ");

        String id = id(postJson(ADDRESSES, body, customer)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Home"))
                .andExpect(jsonPath("$.phone").value("+94771234567"))
                .andExpect(jsonPath("$.district").value("COLOMBO"))
                .andExpect(jsonPath("$.postalCode").value("10250"))
                .andExpect(jsonPath("$.landmark").value(nullValue()))
                .andExpect(jsonPath("$.isDefault").value(false))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(get(ADDRESSES).cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].city").value("Nugegoda"));
        assertThat(jdbc.queryForObject("SELECT phone FROM customer.customer_addresses WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("+94771234567");
    }

    @Test
    void theDefaultPickupAddressIsListedFirstAndThereIsOnlyOne() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String home = save(customer, address("Home", true));
        String shop = save(customer, address("Shop", false));
        String parents = save(customer, address("Parents", true));

        mvc.perform(get(ADDRESSES).cookie(customer))
                .andExpect(jsonPath("$[*].label").value(org.hamcrest.Matchers.contains("Parents", "Home", "Shop")))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].isDefault").value(false))
                .andExpect(jsonPath("$[2].isDefault").value(false));

        mvc.perform(put(ADDRESSES + "/{id}/default", shop).cookie(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get(ADDRESSES).cookie(customer))
                .andExpect(jsonPath("$[0].id").value(shop))
                .andExpect(jsonPath("$[?(@.isDefault == true)]", hasSize(1)));
        mvc.perform(get("/api/v1/customer/profile").cookie(customer))
                .andExpect(jsonPath("$.defaultPickupAddressId").value(shop))
                .andExpect(jsonPath("$.savedAddressesCount").value(3));
        assertThat(home).isNotEqualTo(parents);
    }

    // ---- edit ----

    @Test
    void anEditReplacesTheFieldsAndCanStopItBeingTheDefault() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String id = save(customer, address("Home", true));
        Map<String, Object> changed = address("Office", false);
        changed.put("addressLine", "118 Galle Road");
        changed.put("city", "Dehiwala");
        changed.put("postalCode", "10350");
        changed.put("landmark", "Next to the bank");

        putJson(ADDRESSES + "/" + id, changed, customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Office"))
                .andExpect(jsonPath("$.addressLine").value("118 Galle Road"))
                .andExpect(jsonPath("$.landmark").value("Next to the bank"))
                .andExpect(jsonPath("$.isDefault").value(false));
        mvc.perform(get("/api/v1/customer/profile").cookie(customer))
                .andExpect(jsonPath("$.defaultPickupAddressId").value(nullValue()));
    }

    @Test
    void anEditWithoutUseAsDefaultLeavesTheDefaultAsItIs() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String id = save(customer, address("Home", true));
        Map<String, Object> changed = address("Home", false);
        changed.remove("useAsDefault");

        putJson(ADDRESSES + "/" + id, changed, customer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    // ---- delete ----

    @Test
    void deletingTheDefaultAddressLeavesNoDefault() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        String home = save(customer, address("Home", true));
        save(customer, address("Shop", false));

        mvc.perform(delete(ADDRESSES + "/{id}", home).cookie(customer))
                .andExpect(status().isNoContent());

        mvc.perform(get(ADDRESSES).cookie(customer))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].label").value("Shop"))
                .andExpect(jsonPath("$[0].isDefault").value(false));
        mvc.perform(get("/api/v1/customer/profile").cookie(customer))
                .andExpect(jsonPath("$.defaultPickupAddressId").value(nullValue()))
                .andExpect(jsonPath("$.savedAddressesCount").value(1));
    }

    // ---- someone else's address ----

    @Test
    void someoneElsesAddressAndAnUnknownIdAreNotFoundAndUnchanged() throws Exception {
        String theirs = save(registerCustomer(uniqueEmail(), uniquePhone()), address("Home", false));
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        for (String id : new String[] {theirs, UUID.randomUUID().toString()}) {
            putJson(ADDRESSES + "/" + id, address("Mine now", false), customer)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            mvc.perform(put(ADDRESSES + "/{id}/default", id).cookie(customer))
                    .andExpect(status().isNotFound());
            mvc.perform(delete(ADDRESSES + "/{id}", id).cookie(customer))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(ADDRESSES).cookie(customer))
                .andExpect(jsonPath("$", hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT label FROM customer.customer_addresses WHERE id = ?", String.class,
                UUID.fromString(theirs))).isEqualTo("Home");
    }

    // ---- rules ----

    @Test
    void everyFieldIsValidated() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        Map<String, Object> body = new HashMap<>();
        body.put("label", " ");
        body.put("contactName", "");
        body.put("phone", "12345");
        body.put("addressLine", "");
        body.put("city", "");
        body.put("postalCode", "1025");
        body.put("landmark", "x".repeat(151));

        postJson(ADDRESSES, body, customer)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.label").value("Enter a label, like Home or Office."))
                .andExpect(jsonPath("$.fieldErrors.contactName").value("Enter a name."))
                .andExpect(jsonPath("$.fieldErrors.phone")
                        .value("Enter a Sri Lankan mobile number, like 077 123 4567."))
                .andExpect(jsonPath("$.fieldErrors.addressLine").value("Enter the address."))
                .andExpect(jsonPath("$.fieldErrors.city").value("Enter the city."))
                .andExpect(jsonPath("$.fieldErrors.district").value("Select a district."))
                .andExpect(jsonPath("$.fieldErrors.postalCode").value("Enter the 5-digit postal code."))
                .andExpect(jsonPath("$.fieldErrors.landmark").value("Use 150 characters or fewer."));
    }

    @Test
    void aCustomerCanSaveTwentyAddressesButNotMore() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());
        for (int i = 1; i <= 20; i++) {
            save(customer, address("Address " + i, false));
        }

        postJson(ADDRESSES, address("One too many", false), customer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADDRESS_LIMIT_REACHED"));
    }

    // ---- helpers ----

    private static Map<String, Object> address(String label, boolean useAsDefault) {
        Map<String, Object> body = new HashMap<>();
        body.put("label", label);
        body.put("contactName", "Nimali Perera");
        body.put("phone", uniquePhone());
        body.put("addressLine", "24 Temple Road");
        body.put("city", "Nugegoda");
        body.put("district", "COLOMBO");
        body.put("postalCode", "10250");
        body.put("useAsDefault", useAsDefault);
        return body;
    }

    private String save(Cookie customer, Map<String, Object> body) throws Exception {
        return id(postJson(ADDRESSES, body, customer)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private String id(String response) {
        return json.readTree(response).get("id").asString();
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String path, Object body, Cookie customer)
            throws Exception {
        return mvc.perform(put(path).cookie(customer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }
}
