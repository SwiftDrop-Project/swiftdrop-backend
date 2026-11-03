package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.identity.internal.application.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordRecoveryTest extends IntegrationTest {

    private static final String FORGOT = "/api/v1/auth/password/forgot";
    private static final String RESET = "/api/v1/auth/password/reset";
    private static final String FORGOT_MESSAGE = "If an account matches, we've sent a code to its email.";
    private static final String NEW_PASSWORD = "brandNew789";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserService userService;

    @Test
    void resetByEmailSetsTheNewPasswordAndEndsEverySession() throws Exception {
        String email = uniqueEmail();
        Cookie oldSession = registerCustomer(email, uniquePhone());
        mvc.perform(get("/api/v1/auth/me").cookie(oldSession)).andExpect(status().isOk());

        postJson(FORGOT, Map.of("identifier", email.toUpperCase()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE));
        String code = mail.awaitCode(email, RESET_SUBJECT);

        postJson(RESET, Map.of("identifier", email, "code", code, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));

        mvc.perform(get("/api/v1/auth/me").cookie(oldSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        login(email, PASSWORD, false).andExpect(status().isUnauthorized());
        login(email, NEW_PASSWORD, false).andExpect(status().isOk());
        assertThat(mail.awaitMessages(email, CHANGED_SUBJECT, 1).getFirst().text())
                .contains("password of your SwiftDrop account was just changed");
        // The code is used up.
        postJson(RESET, Map.of("identifier", email, "code", code, "newPassword", "another123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    @Test
    void forgotByMobileNumberEmailsTheAccount() throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();
        registerCustomer(email, phone);

        postJson(FORGOT, Map.of("identifier", stored(phone)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE));
        String code = mail.awaitCode(email, RESET_SUBJECT);

        postJson(RESET, Map.of("identifier", phone, "code", code, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent());
        login(phone, NEW_PASSWORD, false).andExpect(status().isOk());
    }

    @Test
    void unknownIdentifierGetsTheSame202AndNoEmail() throws Exception {
        String unknownEmail = uniqueEmail();
        String knownBody = postJson(FORGOT, Map.of("identifier", "0719999999"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String unknownBody = postJson(FORGOT, Map.of("identifier", unknownEmail))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(unknownBody).isEqualTo(knownBody).contains(FORGOT_MESSAGE);
        Thread.sleep(300);
        assertThat(mail.messagesTo(unknownEmail)).isEmpty();
        postJson(RESET, Map.of("identifier", unknownEmail, "code", "123456", "newPassword", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    @Test
    void newPasswordFollowsTheRegisterRules() throws Exception {
        postJson(RESET, Map.of("identifier", "", "code", "abc", "newPassword", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.identifier").value("Enter your mobile number or email."))
                .andExpect(jsonPath("$.fieldErrors.code").value("Enter the 6-digit code."))
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("Use at least 8 characters, including a number."));
        postJson(FORGOT, Map.of("identifier", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.identifier").value("Enter your mobile number or email."));
    }

    @Test
    void googleOnlyAccountCanSetAFirstPassword() throws Exception {
        String email = uniqueEmail();
        userService.findOrCreateGoogleUser("google-" + UUID.randomUUID(), email, "Kamal Silva", Role.CUSTOMER,
                UserStatus.ACTIVE);
        login(email, NEW_PASSWORD, false).andExpect(status().isUnauthorized());

        postJson(FORGOT, Map.of("identifier", email)).andExpect(status().isAccepted());
        String code = mail.awaitCode(email, RESET_SUBJECT);
        postJson(RESET, Map.of("identifier", email, "code", code, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent());

        login(email, NEW_PASSWORD, false).andExpect(status().isOk());
    }

    @Test
    void resetOnAnUnverifiedAccountAlsoVerifiesIt() throws Exception {
        String email = uniqueEmail();
        register(customerBody(email, uniquePhone())).andExpect(status().isCreated());
        String verificationCode = mail.awaitCode(email, VERIFICATION_SUBJECT);

        postJson(FORGOT, Map.of("identifier", email)).andExpect(status().isAccepted());
        String resetCode = mail.awaitCode(email, RESET_SUBJECT);
        postJson(RESET, Map.of("identifier", email, "code", resetCode, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select status from identity.users where email = ?", String.class, email))
                .isEqualTo("ACTIVE");
        login(email, NEW_PASSWORD, false).andExpect(status().isOk());
        postJson(VERIFY_PATH, Map.of("email", email, "code", verificationCode))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    @Test
    void wrongResetCodesAreCountedLikeVerificationCodes() throws Exception {
        String email = uniqueEmail();
        registerCustomer(email, uniquePhone());
        postJson(FORGOT, Map.of("identifier", email)).andExpect(status().isAccepted());
        String code = mail.awaitCode(email, RESET_SUBJECT);
        String wrong = code.equals("999999") ? "888888" : "999999";

        for (int i = 0; i < 4; i++) {
            postJson(RESET, Map.of("identifier", email, "code", wrong, "newPassword", NEW_PASSWORD))
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }
        postJson(RESET, Map.of("identifier", email, "code", wrong, "newPassword", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        login(email, PASSWORD, false).andExpect(status().isOk());
    }
}
