package com.swiftdrop.identity;

import com.swiftdrop.CapturingMailSender;
import com.swiftdrop.CapturingMailSender.Captured;
import com.swiftdrop.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailVerificationTest extends IntegrationTest {

    private static final String RESEND = "/api/v1/auth/email-verification/resend";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void customerVerifiesWithTheEmailedCodeAndIsLoggedIn() throws Exception {
        String email = uniqueEmail();
        register(customerBody(email, uniquePhone()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_EMAIL_VERIFICATION"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        Captured message = mail.awaitMessages(email, VERIFICATION_SUBJECT, 1).getFirst();
        String code = CapturingMailSender.codeIn(message);
        assertThat(code).matches("\\d{6}");
        assertThat(message.from()).contains("SwiftDrop").contains("no-reply@test.swiftdrop.lk");
        assertThat(message.text()).contains("Hi Ayesha Fernando,", code, "10 minutes");
        assertThat(message.html())
                .contains("SwiftDrop", "#CC4A00", code, "-apple-system")
                .doesNotContain("{{");

        MvcResult result = postJson(VERIFY_PATH, Map.of("email", email.toUpperCase(), "code", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        Cookie cookie = tokenCookie(result);
        mvc.perform(get("/api/v1/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        login(email, PASSWORD, false).andExpect(status().isOk());

        // A used code cannot be used again.
        postJson(VERIFY_PATH, Map.of("email", email, "code", code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    @Test
    void merchantBecomesPendingApprovalWithoutCookieAndIsHiddenFromAdminsUntilVerified() throws Exception {
        String email = uniqueEmail();
        String userId = json.readTree(register(merchantBody(email, uniquePhone()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
        Cookie admin = loginAsAdmin();

        mvc.perform(get("/api/v1/admin/merchants").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == '" + userId + "')]").isEmpty());

        MvcResult result = verifyEmail(email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MERCHANT"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.merchant.approvalStatus").value("PENDING"))
                .andReturn();
        assertThat(setCookieHeader(result)).isNull();

        mvc.perform(get("/api/v1/admin/merchants").cookie(admin))
                .andExpect(jsonPath("$[?(@.userId == '" + userId + "')]").isNotEmpty());
        login(email, PASSWORD, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_APPROVAL"));
    }

    @Test
    void fiveWrongCodesKillTheCode() throws Exception {
        String email = uniqueEmail();
        register(customerBody(email, uniquePhone())).andExpect(status().isCreated());
        String code = mail.awaitCode(email, VERIFICATION_SUBJECT);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 1; i <= 4; i++) {
            postJson(VERIFY_PATH, Map.of("email", email, "code", wrong))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"))
                    .andExpect(jsonPath("$.message").value("That code isn't right. Check it and try again."));
        }
        postJson(VERIFY_PATH, Map.of("email", email, "code", wrong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        // Even the right code is refused now; a new code is needed.
        postJson(VERIFY_PATH, Map.of("email", email, "code", code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        assertThat(jdbc.queryForObject("select status from identity.users where email = ?", String.class, email))
                .isEqualTo("PENDING_EMAIL_VERIFICATION");
    }

    @Test
    void expiredCodeIsRefused() throws Exception {
        String email = uniqueEmail();
        register(customerBody(email, uniquePhone())).andExpect(status().isCreated());
        String code = mail.awaitCode(email, VERIFICATION_SUBJECT);
        jdbc.update("""
                update identity.verification_codes set expires_at = now() - interval '1 minute'
                where user_id = (select id from identity.users where email = ?)""", email);

        postJson(VERIFY_PATH, Map.of("email", email, "code", code))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"))
                .andExpect(jsonPath("$.message").value("This code has expired. Ask for a new one."));
    }

    @Test
    void resendHasA60SecondCooldownAndReplacesTheOldCode() throws Exception {
        String email = uniqueEmail();
        register(customerBody(email, uniquePhone())).andExpect(status().isCreated());
        String first = mail.awaitCode(email, VERIFICATION_SUBJECT);

        postJson(RESEND, Map.of("email", email))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        "If this email is waiting for verification, we've sent a new code."));
        Thread.sleep(300);
        assertThat(mail.messagesTo(email)).hasSize(1);

        backdateCodes(email, 61);
        postJson(RESEND, Map.of("email", email)).andExpect(status().isAccepted());
        List<Captured> messages = mail.awaitMessages(email, VERIFICATION_SUBJECT, 2);
        String second = CapturingMailSender.codeIn(messages.getLast());

        if (!first.equals(second)) {
            postJson(VERIFY_PATH, Map.of("email", email, "code", first))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }
        postJson(VERIFY_PATH, Map.of("email", email, "code", second)).andExpect(status().isOk());
    }

    @Test
    void atMostFiveCodesPerHour() throws Exception {
        String email = uniqueEmail();
        register(customerBody(email, uniquePhone())).andExpect(status().isCreated());
        mail.awaitMessages(email, VERIFICATION_SUBJECT, 1);

        for (int sent = 2; sent <= 5; sent++) {
            backdateCodes(email, 61);
            postJson(RESEND, Map.of("email", email)).andExpect(status().isAccepted());
            mail.awaitMessages(email, VERIFICATION_SUBJECT, sent);
        }
        backdateCodes(email, 61);
        postJson(RESEND, Map.of("email", email)).andExpect(status().isAccepted());
        Thread.sleep(300);
        assertThat(mail.messagesTo(email)).hasSize(5);
    }

    @Test
    void resendAndVerifyRevealNothingAboutUnknownOrVerifiedEmails() throws Exception {
        String unknown = uniqueEmail();
        postJson(RESEND, Map.of("email", unknown))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        "If this email is waiting for verification, we've sent a new code."));
        postJson(VERIFY_PATH, Map.of("email", unknown, "code", "123456"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        String verified = uniqueEmail();
        registerCustomer(verified, uniquePhone());
        postJson(RESEND, Map.of("email", verified)).andExpect(status().isAccepted());
        Thread.sleep(300);
        assertThat(mail.messagesTo(unknown)).isEmpty();
        assertThat(mail.messagesTo(verified)).hasSize(1);
    }

    @Test
    void codesAreStoredOnlyAsHmac() throws Exception {
        String email = uniqueEmail();
        register(customerBody(email, uniquePhone())).andExpect(status().isCreated());
        String code = mail.awaitCode(email, VERIFICATION_SUBJECT);

        Map<String, Object> row = jdbc.queryForMap("""
                select c.code_hash, c.purpose, c.attempts, c.consumed_at,
                       extract(epoch from c.expires_at - c.created_at) as lifetime
                from identity.verification_codes c join identity.users u on u.id = c.user_id
                where u.email = ?""", email);
        assertThat((String) row.get("code_hash")).matches("[0-9a-f]{64}").doesNotContain(code);
        assertThat(row.get("purpose")).isEqualTo("EMAIL_VERIFICATION");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat(row.get("consumed_at")).isNull();
        assertThat(((Number) row.get("lifetime")).intValue()).isEqualTo(600);
    }

    @Test
    void invalidBodiesReturn400() throws Exception {
        postJson(VERIFY_PATH, Map.of("email", "not-an-email", "code", "12ab"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").value("Enter a full email address, like name@example.com"))
                .andExpect(jsonPath("$.fieldErrors.code").value("Enter the 6-digit code."));
        postJson(RESEND, Map.of("email", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    /** Moves the user's codes back in time, to get past the 60-second resend cooldown without waiting. */
    private void backdateCodes(String email, int seconds) {
        jdbc.update("""
                update identity.verification_codes set created_at = created_at - make_interval(secs => ?)
                where user_id = (select id from identity.users where email = ?)""", seconds, email);
    }
}
