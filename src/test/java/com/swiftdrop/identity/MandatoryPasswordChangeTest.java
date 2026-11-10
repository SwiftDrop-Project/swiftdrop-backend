package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MandatoryPasswordChangeTest extends IntegrationTest {

    private static final String CHANGE = "/api/v1/auth/password/change";

    @Test
    void staffMustChangeInitialPasswordBeforeUsingProtectedEndpoints() throws Exception {
        Cookie admin = loginAsAdmin();
        String courierEmail = uniqueEmail();
        String courierPhone = uniquePhone();

        postJson("/api/v1/admin/couriers", Map.of(
                "fullName", "Chathura Perera",
                "email", courierEmail,
                "phone", courierPhone,
                "branch", "COLOMBO",
                "vehicleType", "MOTORBIKE",
                "password", "TempInit123"), admin)
                .andExpect(status().isCreated());

        MvcResult loginResult = login(courierEmail, "TempInit123", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("COURIER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();
        Cookie tempCookie = tokenCookie(loginResult);

        // /auth/me is allowed while mustChangePassword=true
        mvc.perform(get("/api/v1/auth/me").cookie(tempCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));

        // Protected courier endpoint is blocked with 403 PASSWORD_CHANGE_REQUIRED
        mvc.perform(get("/api/v1/courier/deliveries/today").cookie(tempCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

        // Reusing the same initial password is rejected with a field error on newPassword
        postJson(CHANGE, Map.of("currentPassword", "TempInit123", "newPassword", "TempInit123"), tempCookie)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.newPassword")
                        .value("Choose a different password from your current one."));

        // Changing to a new valid password clears mustChangePassword and refreshes SD_TOKEN
        MvcResult changed = postJson(CHANGE,
                Map.of("currentPassword", "TempInit123", "newPassword", "CourierOwn456"), tempCookie)
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie freshCookie = tokenCookie(changed);

        mvc.perform(get("/api/v1/auth/me").cookie(freshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));

        // Courier endpoint now responds 200 OK
        mvc.perform(get("/api/v1/courier/deliveries/today").cookie(freshCookie))
                .andExpect(status().isOk());

        // Pre-change cookie is invalidated
        mvc.perform(get("/api/v1/auth/me").cookie(tempCookie))
                .andExpect(status().isUnauthorized());

        // Logout clears cookie cleanly even for a dispatcher with mustChangePassword=true
        String dispatcherEmail = uniqueEmail();
        postJson("/api/v1/admin/dispatchers", Map.of(
                "fullName", "Malsha Fernando",
                "email", dispatcherEmail,
                "phone", uniquePhone(),
                "branch", "GALLE",
                "password", "TempDisp123"), admin)
                .andExpect(status().isCreated());
        Cookie dispTemp = tokenCookie(login(dispatcherEmail, "TempDisp123", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn());
        mvc.perform(get("/api/v1/dispatch/couriers").cookie(dispTemp))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(post("/api/v1/auth/logout").cookie(dispTemp))
                .andExpect(status().isNoContent());
    }

    @Test
    void passwordRecoveryCannotBypassMandatoryPasswordChangeOrReactivateBlockedOrRemovedAccounts() throws Exception {
        Cookie admin = loginAsAdmin();
        String freshStaffEmail = uniqueEmail();
        String blockedEmail = uniqueEmail();
        String removedEmail = uniqueEmail();

        postJson("/api/v1/admin/couriers", Map.of(
                "fullName", "Fresh Staff",
                "email", freshStaffEmail,
                "phone", uniquePhone(),
                "branch", "COLOMBO",
                "vehicleType", "MOTORBIKE",
                "password", "InitFresh123"), admin)
                .andExpect(status().isCreated());

        // Staff who still owe their mandatory password change cannot request a public password-reset code to bypass it
        postJson("/api/v1/auth/password/forgot", Map.of("identifier", freshStaffEmail))
                .andExpect(status().isAccepted());
        Thread.sleep(250);
        assertThat(mail.messagesTo(freshStaffEmail)).isEmpty();

        String blockedId = json.readTree(postJson("/api/v1/admin/couriers", Map.of(
                "fullName", "Blocked Courier",
                "email", blockedEmail,
                "phone", uniquePhone(),
                "branch", "COLOMBO",
                "vehicleType", "VAN",
                "password", "InitBlock123"), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        String removedId = json.readTree(postJson("/api/v1/admin/couriers", Map.of(
                "fullName", "Removed Courier",
                "email", removedEmail,
                "phone", uniquePhone(),
                "branch", "COLOMBO",
                "vehicleType", "VAN",
                "password", "InitRemove123"), admin)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        // Complete initial password change so blockedEmail has mustChangePassword=false, then request a reset code
        Cookie blockedInit = tokenCookie(login(blockedEmail, "InitBlock123", false).andExpect(status().isOk()).andReturn());
        postJson(CHANGE, Map.of("currentPassword", "InitBlock123", "newPassword", "PersonalBlock456"), blockedInit)
                .andExpect(status().isNoContent());

        postJson("/api/v1/auth/password/forgot", Map.of("identifier", blockedEmail))
                .andExpect(status().isAccepted());
        String preBlockCode = mail.awaitCode(blockedEmail, RESET_SUBJECT);

        // Blocking invalidates active reset codes (400 INVALID_CODE) and keeps the account blocked (403 ACCOUNT_DISABLED)
        mvc.perform(post("/api/v1/admin/couriers/{id}/block", blockedId).cookie(admin))
                .andExpect(status().isOk());
        postJson("/api/v1/auth/password/reset", Map.of(
                "identifier", blockedEmail,
                "code", preBlockCode,
                "newPassword", "AttemptedNew789"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        login(blockedEmail, "AttemptedNew789", false)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login(blockedEmail, "PersonalBlock456", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        // Soft-remove courier and verify forgot sends no email while still returning 202
        mvc.perform(delete("/api/v1/admin/couriers/{id}", removedId).cookie(admin))
                .andExpect(status().isOk());
        postJson("/api/v1/auth/password/forgot", Map.of("identifier", removedEmail))
                .andExpect(status().isAccepted());
        Thread.sleep(250);
        assertThat(mail.messagesTo(removedEmail)).isEmpty();
    }
}
