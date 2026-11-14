package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /admin/users. The test database is shared by all tests, so lists are checked by searching for this test's own
 * accounts, and counts are checked as differences.
 */
class AdminUserManagementTest extends IntegrationTest {

    private static final String USERS = "/api/v1/admin/users";

    // ---- Access ----

    @Test
    void everyEndpointNeedsALogin() throws Exception {
        mvc.perform(get(USERS)).andExpect(status().isUnauthorized());
        mvc.perform(get(USERS + "/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get(USERS + "/{id}", java.util.UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsMayUseIt() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(USERS).cookie(customer)).andExpect(status().isForbidden());
        mvc.perform(get(USERS + "/stats").cookie(customer)).andExpect(status().isForbidden());
        patchStatus(java.util.UUID.randomUUID().toString(), "BLOCKED", customer).andExpect(status().isForbidden());
    }

    // ---- List, search and filters ----

    @Test
    void findsAnAccountByEmailPhoneOrIdWithoutSecrets() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        String phone = uniquePhone();
        registerCustomer(email, phone);
        String id = idOf(admin, email);

        mvc.perform(get(USERS).param("q", email.toUpperCase()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].email").value(email))
                .andExpect(jsonPath("$.content[0].phone").value(stored(phone)))
                .andExpect(jsonPath("$.content[0].role").value("CUSTOMER"))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].passwordHash").doesNotExist());

        // local phone format finds the stored +94 number
        mvc.perform(get(USERS).param("q", phone).cookie(admin))
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));
        // a full user id finds exactly that account
        mvc.perform(get(USERS).param("q", id).cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
    }

    @Test
    void filtersByRoleStatusAndRegistrationDate() throws Exception {
        Cookie admin = loginAsAdmin();
        String merchantEmail = uniqueEmail();
        String merchantId = registerMerchant(merchantEmail, uniquePhone());

        mvc.perform(get(USERS).param("q", merchantEmail).param("role", "MERCHANT").param("status", "PENDING")
                        .cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(merchantId)))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_APPROVAL"));

        mvc.perform(get(USERS).param("q", merchantEmail).param("role", "CUSTOMER").cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(USERS).param("q", merchantEmail).param("status", "ACTIVE").cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(USERS).param("q", merchantEmail).param("registeredTo", "2020-01-01").cookie(admin))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(USERS).param("q", merchantEmail).param("registeredFrom", "2020-01-01").cookie(admin))
                .andExpect(jsonPath("$.content[*].id", hasItem(merchantId)));
    }

    @Test
    void sortsByNameAndRejectsBadFilters() throws Exception {
        Cookie admin = loginAsAdmin();

        String body = mvc.perform(get(USERS).param("sort", "NAME").param("size", "100").cookie(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var names = new java.util.ArrayList<String>();
        json.readTree(body).get("content").forEach(user -> names.add(user.get("fullName").asString().toLowerCase()));
        assertThat(names).isSorted();

        mvc.perform(get(USERS).param("registeredFrom", "2026-11-10").param("registeredTo", "2026-11-01").cookie(admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get(USERS).param("role", "SUPERUSER").cookie(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(USERS).param("sort", "RANDOM").cookie(admin)).andExpect(status().isBadRequest());
    }

    // ---- Details ----

    @Test
    void showsMerchantBusinessDetailsAndNoSecrets() throws Exception {
        Cookie admin = loginAsAdmin();
        String merchantId = registerMerchant(uniqueEmail(), uniquePhone());

        mvc.perform(get(USERS + "/{id}", merchantId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MERCHANT"))
                .andExpect(jsonPath("$.merchant.businessName").value("Lanka Crafts"))
                .andExpect(jsonPath("$.merchant.pickupDistrict").value("COLOMBO"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.googleLinked").value(false))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.googleSub").doesNotExist())
                .andExpect(jsonPath("$.tokenVersion").doesNotExist());

        mvc.perform(get(USERS + "/{id}", java.util.UUID.randomUUID()).cookie(admin))
                .andExpect(status().isNotFound());
    }

    // ---- Edit ----

    @Test
    void editsNameEmailAndPhone() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());
        String id = idOf(admin, email);
        String newEmail = uniqueEmail();
        String newPhone = uniquePhone();

        mvc.perform(put(USERS + "/{id}", id).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Kasun Perera", "email", newEmail,
                                "phone", newPhone))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Kasun Perera"))
                .andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.phone").value(stored(newPhone)))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void editRejectsAnotherAccountsPhoneAndBadInput() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());
        String id = idOf(admin, email);
        String takenPhone = uniquePhone();
        registerCustomer(uniqueEmail(), takenPhone);

        mvc.perform(put(USERS + "/{id}", id).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "Kasun Perera", "email", email,
                                "phone", takenPhone))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));

        mvc.perform(put(USERS + "/{id}", id).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("fullName", "", "email", "not-an-email",
                                "phone", "123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- Block and unblock ----

    @Test
    void blockingLogsTheUserOutAndUnblockingLetsThemBackIn() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        Cookie customer = registerCustomer(email, uniquePhone());
        String id = idOf(admin, email);
        mvc.perform(get("/api/v1/auth/me").cookie(customer)).andExpect(status().isOk());

        patchStatus(id, "BLOCKED", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(get("/api/v1/auth/me").cookie(customer)).andExpect(status().isUnauthorized());
        login(email, PASSWORD, false).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mvc.perform(get(USERS).param("q", email).param("status", "BLOCKED").cookie(admin))
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));

        patchStatus(id, "ACTIVE", admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        login(email, PASSWORD, false).andExpect(status().isOk());
    }

    @Test
    void blockingIsRefusedForPendingAccountsAndTheAdminsOwnAccount() throws Exception {
        Cookie admin = loginAsAdmin();
        String merchantId = registerMerchant(uniqueEmail(), uniquePhone());

        patchStatus(merchantId, "BLOCKED", admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        patchStatus(merchantId, "ACTIVE", admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_BLOCKED"));

        String adminId = idOf(admin, ADMIN_EMAIL);
        patchStatus(adminId, "BLOCKED", admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_ACCOUNT"));
        mvc.perform(get("/api/v1/auth/me").cookie(admin)).andExpect(status().isOk());

        mvc.perform(patch(USERS + "/{id}/status", merchantId).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- Stats ----

    @Test
    void statsFollowNewAndBlockedAccounts() throws Exception {
        Cookie admin = loginAsAdmin();
        JsonNode before = stats(admin);

        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());
        registerMerchant(uniqueEmail(), uniquePhone());
        JsonNode afterSignUps = stats(admin);
        assertThat(diff(afterSignUps, before, "total")).isEqualTo(2);
        assertThat(diff(afterSignUps, before, "active")).isEqualTo(1);
        assertThat(diff(afterSignUps, before, "pending")).isEqualTo(1);
        assertThat(diff(afterSignUps, before, "newLast30Days")).isEqualTo(2);

        patchStatus(idOf(admin, email), "BLOCKED", admin).andExpect(status().isOk());
        JsonNode afterBlock = stats(admin);
        assertThat(diff(afterBlock, afterSignUps, "active")).isEqualTo(-1);
        assertThat(diff(afterBlock, afterSignUps, "blocked")).isEqualTo(1);
        assertThat(diff(afterBlock, afterSignUps, "total")).isZero();
    }

    @Test
    void removedAccountsAreHiddenUnlessAskedFor() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        String body = postJson("/api/v1/admin/couriers", Map.of("fullName", "Amal Fernando", "email", email,
                "phone", uniquePhone(), "branch", "COLOMBO", "vehicleType", "MOTORBIKE", "password", "InitCourier9"),
                admin).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String courierId = json.readTree(body).get("id").asString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/admin/couriers/{id}", courierId).cookie(admin)).andExpect(status().isOk());

        mvc.perform(get(USERS).param("q", email).cookie(admin))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(courierId))));
        mvc.perform(get(USERS).param("q", email).param("status", "REMOVED").cookie(admin))
                .andExpect(jsonPath("$.content[*].id", hasItem(courierId)));
        patchStatus(courierId, "ACTIVE", admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REMOVED"));
    }

    // ---- Helpers ----

    private ResultActions patchStatus(String id, String status, Cookie cookie) throws Exception {
        return mvc.perform(patch(USERS + "/{id}/status", id).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("status", status))));
    }

    /** The id of the account with this email (searched through the API, as the admin page does). */
    private String idOf(Cookie admin, String email) throws Exception {
        String body = mvc.perform(get(USERS).param("q", email).cookie(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode content = json.readTree(body).get("content");
        assertThat(content.size()).as("accounts matching " + email).isEqualTo(1);
        return content.get(0).get("id").asString();
    }

    private JsonNode stats(Cookie admin) throws Exception {
        String body = mvc.perform(get(USERS + "/stats").cookie(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static long diff(JsonNode after, JsonNode before, String field) {
        return after.get(field).asLong() - before.get(field).asLong();
    }
}
