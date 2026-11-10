package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class AdminCourierManagementTest extends IntegrationTest {

    private static final String COURIERS = "/api/v1/admin/couriers";

    @Autowired
    JdbcTemplate jdbc;

    private static Map<String, Object> courierBody(String fullName, String email, String phone,
                                                   String branch, String vehicleType, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", fullName);
        body.put("email", email);
        body.put("phone", phone);
        body.put("branch", branch);
        body.put("vehicleType", vehicleType);
        body.put("password", password);
        return body;
    }

    @Test
    void createListGetAndFilterCouriersWithoutLoggingPii(CapturedOutput output) throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        String phone = uniquePhone();
        String initialPass = "InitCourier9";

        MvcResult created = postJson(COURIERS,
                courierBody("Kasun Jayasinghe", email.toUpperCase(), phone, "COLOMBO", "MOTORBIKE", initialPass),
                admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Kasun Jayasinghe"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value(stored(phone)))
                .andExpect(jsonPath("$.branch").value("COLOMBO"))
                .andExpect(jsonPath("$.vehicleType").value("MOTORBIKE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.deletedAt").isEmpty())
                .andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asString();

        // Filter by branch + query
        mvc.perform(get(COURIERS).param("branch", "COLOMBO").param("q", "Kasun").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));

        mvc.perform(get(COURIERS).param("branch", "KANDY").param("q", "Kasun").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(id))));

        // Get by ID
        mvc.perform(get(COURIERS + "/{id}", id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.vehicleType").value("MOTORBIKE"));

        // Logs never contain PII or password
        assertThat(output.getAll())
                .doesNotContain(email)
                .doesNotContain(stored(phone))
                .doesNotContain(initialPass);
    }

    @Test
    void nonAdminRolesAndUnauthenticatedCallersAreRejected() throws Exception {
        Cookie customer = registerCustomer(uniqueEmail(), uniquePhone());

        mvc.perform(get(COURIERS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(get(COURIERS).cookie(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        postJson(COURIERS, courierBody("Rogue", uniqueEmail(), uniquePhone(), "COLOMBO", "VAN", "Secret123"), customer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void validationAndDuplicateConstraintsReturnConsistentErrors() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        String phone = uniquePhone();
        postJson(COURIERS, courierBody("Ruwan Silva", email, phone, "GALLE", "VAN", "Courier123"), admin)
                .andExpect(status().isCreated());

        // Duplicate email
        postJson(COURIERS, courierBody("Another", email, uniquePhone(), "GALLE", "VAN", "Courier123"), admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

        // Duplicate phone
        postJson(COURIERS, courierBody("Another", uniqueEmail(), phone, "GALLE", "VAN", "Courier123"), admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));

        // Invalid enum value
        postJson(COURIERS, courierBody("Another", uniqueEmail(), uniquePhone(), "COLOMBO", "BICYCLE", "Courier123"), admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.vehicleType").value("Select a vehicle type."));
    }

    @Test
    void updateCourierEmailRevokesActiveSessionsAndUnlinksGoogle() throws Exception {
        Cookie admin = loginAsAdmin();
        String oldEmail = uniqueEmail();
        String newEmail = uniqueEmail();
        String phone = uniquePhone();
        String updatedPhone = uniquePhone();

        String body = postJson(COURIERS,
                courierBody("Dinesh Perera", oldEmail, phone, "COLOMBO", "THREE_WHEELER", "TempPass123"), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();

        // Simulate a linked google_sub and an active session for the courier
        jdbc.update("UPDATE identity.users SET google_sub = ? WHERE id = ?::uuid", "google-old-" + id, id);
        Cookie courierSession = tokenCookie(login(oldEmail, "TempPass123", false).andExpect(status().isOk()).andReturn());
        mvc.perform(get("/api/v1/auth/me").cookie(courierSession)).andExpect(status().isOk());

        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("fullName", "Dinesh K. Perera");
        updateBody.put("email", newEmail.toUpperCase());
        updateBody.put("phone", updatedPhone);
        updateBody.put("branch", "KANDY");
        updateBody.put("vehicleType", "LORRY");

        mvc.perform(put(COURIERS + "/{id}", id)
                        .cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Dinesh K. Perera"))
                .andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.phone").value(stored(updatedPhone)))
                .andExpect(jsonPath("$.branch").value("KANDY"))
                .andExpect(jsonPath("$.vehicleType").value("LORRY"));

        // Old session cookie is immediately revoked, google_sub is cleared, and login works only with newEmail
        mvc.perform(get("/api/v1/auth/me").cookie(courierSession)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT google_sub FROM identity.users WHERE id = ?::uuid",
                String.class, id)).isNull();
        login(oldEmail, "TempPass123", false).andExpect(status().isUnauthorized());
        login(newEmail, "TempPass123", false).andExpect(status().isOk());
    }

    @Test
    void blockUnblockResetPasswordAndPermanentSoftRemoveLifecycle() throws Exception {
        Cookie admin = loginAsAdmin();
        String email = uniqueEmail();
        String phone = uniquePhone();

        String created = postJson(COURIERS,
                courierBody("Nuwan Bandara", email, phone, "GAMPAHA", "MOTORBIKE", "Initial123"), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asString();

        // Courier logs in and changes initial password
        Cookie firstSession = tokenCookie(login(email, "Initial123", false).andExpect(status().isOk()).andReturn());
        Cookie activeSession = tokenCookie(postJson("/api/v1/auth/password/change",
                Map.of("currentPassword", "Initial123", "newPassword", "Personal456"), firstSession)
                .andExpect(status().isNoContent())
                .andReturn());
        mvc.perform(get("/api/v1/auth/me").cookie(activeSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));

        // Block courier -> active session ends immediately and login fails with 403 ACCOUNT_DISABLED
        mvc.perform(post(COURIERS + "/{id}/block", id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(get("/api/v1/auth/me").cookie(activeSession)).andExpect(status().isUnauthorized());
        login(email, "Personal456", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        // Unblock courier -> old session stays invalid, fresh login succeeds
        mvc.perform(post(COURIERS + "/{id}/unblock", id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/auth/me").cookie(activeSession)).andExpect(status().isUnauthorized());
        Cookie postUnblockSession = tokenCookie(login(email, "Personal456", false).andExpect(status().isOk()).andReturn());

        // Admin resets password -> existing session invalidated and mustChangePassword=true again
        postJson(COURIERS + "/" + id + "/reset-password", Map.of("newPassword", "AdminReset789"), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(get("/api/v1/auth/me").cookie(postUnblockSession)).andExpect(status().isUnauthorized());
        Cookie afterResetSession = tokenCookie(login(email, "AdminReset789", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn());

        // Soft-remove courier -> leaves default list, shows under status=REMOVED, revokes session, blocks all mutations
        mvc.perform(delete(COURIERS + "/{id}", id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty());

        mvc.perform(get("/api/v1/auth/me").cookie(afterResetSession)).andExpect(status().isUnauthorized());
        login(email, "AdminReset789", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mvc.perform(get(COURIERS).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(id))));
        mvc.perform(get(COURIERS).param("status", "REMOVED").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));

        // Removed account cannot be restored via unblock, update, or reset-password
        mvc.perform(post(COURIERS + "/{id}/unblock", id).cookie(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REMOVED"));
        mvc.perform(post(COURIERS + "/{id}/block", id).cookie(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REMOVED"));
        postJson(COURIERS + "/" + id + "/reset-password", Map.of("newPassword", "AnotherPass123"), admin)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REMOVED"));
        mvc.perform(put(COURIERS + "/{id}", id)
                        .cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "fullName", "Nuwan",
                                "email", email,
                                "phone", phone,
                                "branch", "GAMPAHA",
                                "vehicleType", "VAN"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REMOVED"));

        // Unknown courier returns 404
        mvc.perform(get(COURIERS + "/{id}", UUID.randomUUID()).cookie(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}

