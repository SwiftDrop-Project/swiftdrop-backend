package com.swiftdrop.auth;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.users.application.UserService;
import com.swiftdrop.users.domain.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthLoginTest extends IntegrationTest {

    @Autowired
    UserService userService;

    @Test
    void loginWithEmailIgnoresCaseAndSpaces() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());

        login("  " + email.toUpperCase() + " ", PASSWORD, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void loginWithPhoneInEveryAcceptedFormat() throws Exception {
        String phone = uniquePhone();            // 0711234567
        String email = uniqueEmail();
        registerCustomer(email, phone);
        String digits = phone.substring(1);      // 711234567

        List<String> formats = List.of(
                phone,                                                         // 0711234567
                digits,                                                        // 711234567
                "+94" + digits,                                                // +94711234567
                phone.substring(0, 3) + " " + phone.substring(3, 6) + " " + phone.substring(6), // 071 123 4567
                "+94 " + digits.substring(0, 2) + " " + digits.substring(2));  // +94 71 1234567

        for (String identifier : formats) {
            MvcResult result = login(identifier, PASSWORD, false)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(email))
                    .andReturn();
            tokenCookie(result);
        }
    }

    @Test
    void rememberMeSetsPersistentCookie() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());

        String persistent = setCookieHeader(login(email, PASSWORD, true).andExpect(status().isOk()).andReturn());
        assertThat(persistent).contains("Max-Age=604800", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Secure");

        String session = setCookieHeader(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
        assertThat(session).contains("HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Max-Age");
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSame401() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());

        String wrongPassword = login(email, "wrong-pass-1", false)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("The mobile number, email or password is incorrect."))
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = login(uniqueEmail(), PASSWORD, false)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownPhone = login(uniquePhone(), PASSWORD, false)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String notAPhone = login("hello", PASSWORD, false)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        assertThat(List.of(unknownEmail, unknownPhone, notAPhone)).containsOnly(wrongPassword);
    }

    @Test
    void blankFieldsReturn400() throws Exception {
        postJson("/api/v1/auth/login", Map.of("identifier", "", "password", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.identifier").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void pendingMerchantCannotLogInUntilAdminApproves() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());

        login(email, PASSWORD, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_APPROVAL"))
                .andExpect(jsonPath("$.message")
                        .value("Your merchant account is waiting for approval. We'll email you when it's ready."));

        mvc.perform(post("/api/v1/admin/merchants/{id}/approve", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());

        login(email, PASSWORD, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("APPROVED"));
    }

    @Test
    void pendingStatusIsNotRevealedWithWrongPassword() throws Exception {
        String email = uniqueEmail();
        registerMerchant(email, uniquePhone());

        login(email, "wrong-pass-1", false)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void rejectedMerchantCannotLogIn() throws Exception {
        String email = uniqueEmail();
        String userId = registerMerchant(email, uniquePhone());
        mvc.perform(post("/api/v1/admin/merchants/{id}/reject", userId).cookie(loginAsAdmin()))
                .andExpect(status().isOk());

        login(email, PASSWORD, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REJECTED"));
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        String email = uniqueEmail();
        MvcResult registered = register(customerBody(email, uniquePhone())).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json.readTree(registered.getResponse().getContentAsString()).get("id").asString());
        userService.changeStatus(id, UserStatus.DISABLED);

        login(email, PASSWORD, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void brokenCookieDoesNotBlockLogin() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());

        postJson("/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD),
                new Cookie(COOKIE, "not-a-valid-jwt"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutClearsCookie() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, "expired-or-not")))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(setCookieHeader(result)).contains("Max-Age=0", "HttpOnly", "Path=/");
    }
}
