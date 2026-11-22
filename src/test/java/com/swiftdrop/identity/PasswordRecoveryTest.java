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
        private static final String FORGOT_MESSAGE = "If the details match an account, we've sent a code to its registered email.";
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
                                .andExpect(jsonPath("$.fieldErrors.identifier")
                                                .value("Enter your mobile number or email."))
                                .andExpect(jsonPath("$.fieldErrors.code").value("Enter the 6-digit code."))
                                .andExpect(jsonPath("$.fieldErrors.newPassword")
                                                .value("Use at least 8 characters, including a number."));
                postJson(FORGOT, Map.of("identifier", " "))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.fieldErrors.identifier")
                                                .value("Enter your mobile number or email."));
                postJson(FORGOT, Map.of("identifier", "a".repeat(255)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                                .andExpect(jsonPath("$.fieldErrors.identifier")
                                                .value("Enter your mobile number or email."));
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

                assertThat(jdbc.queryForObject("select status from identity.users where email = ?", String.class,
                                email))
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

        @Test
        void phoneWithMatchingConfirmationEmailResetsOnlyThatAccountAndSupportsResend() throws Exception {
                String emailA = uniqueEmail();
                String phoneA = uniquePhone();
                String emailB = uniqueEmail();
                String phoneB = uniquePhone();
                registerCustomer(emailA, phoneA);
                registerCustomer(emailB, phoneB);

                postJson(FORGOT, Map.of("identifier", phoneA, "confirmationEmail", emailA.toUpperCase()))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE));
                String firstCode = mail.awaitCode(emailA, RESET_SUBJECT);
                assertThat(mail.messagesTo(emailB)).hasSize(1); // only registration verification

                // Backdate past the 60-second cooldown and resend with the same phone +
                // confirmationEmail.
                backdateCodes(emailA, 61);
                postJson(FORGOT, Map.of("identifier", stored(phoneA), "confirmationEmail", emailA))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE));
                String secondCode = mail.awaitMessages(emailA, RESET_SUBJECT, 2).getLast().text();
                String latestCode = com.swiftdrop.CapturingMailSender.codeIn(
                                mail.awaitMessages(emailA, RESET_SUBJECT, 2).getLast());
                assertThat(secondCode).contains(latestCode);

                if (!firstCode.equals(latestCode)) {
                        postJson(RESET, Map.of("identifier", phoneA, "code", firstCode, "newPassword", NEW_PASSWORD))
                                        .andExpect(status().isBadRequest())
                                        .andExpect(jsonPath("$.code").value("INVALID_CODE"));
                }

                postJson(RESET, Map.of("identifier", phoneA, "code", latestCode, "newPassword", NEW_PASSWORD))
                                .andExpect(status().isNoContent());

                login(phoneA, PASSWORD, false).andExpect(status().isUnauthorized());
                login(phoneA, NEW_PASSWORD, false).andExpect(status().isOk());
                login(emailA, NEW_PASSWORD, false).andExpect(status().isOk());
                // Account B retains its original credentials and received no reset emails.
                login(emailB, PASSWORD, false).andExpect(status().isOk());
                login(phoneB, PASSWORD, false).andExpect(status().isOk());
                assertThat(mail.messagesTo(emailB)).hasSize(1);
        }

        @Test
        void mismatchedOrUnknownConfirmationEmailReturnsSame202WithoutSendingOrInvalidatingExistingCode()
                        throws Exception {
                String emailA = uniqueEmail();
                String phoneA = uniquePhone();
                String emailB = uniqueEmail();
                String phoneB = uniquePhone();
                String unknownEmail = uniqueEmail();
                registerCustomer(emailA, phoneA);
                registerCustomer(emailB, phoneB);

                // Issue a legitimate reset code for account A first.
                String matchBody = postJson(FORGOT, Map.of("identifier", phoneA, "confirmationEmail", emailA))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.message").value(FORGOT_MESSAGE))
                                .andReturn().getResponse().getContentAsString();
                String codeA = mail.awaitCode(emailA, RESET_SUBJECT);

                // Move past cooldown so a buggy implementation would have invalidated codeA and
                // sent a new code.
                backdateCodes(emailA, 61);

                // Phone A + registered email B (different account)
                String mismatchRegisteredBody = postJson(FORGOT,
                                Map.of("identifier", phoneA, "confirmationEmail", emailB))
                                .andExpect(status().isAccepted())
                                .andReturn().getResponse().getContentAsString();
                // Phone A + unknown email
                String mismatchUnknownBody = postJson(FORGOT,
                                Map.of("identifier", phoneA, "confirmationEmail", unknownEmail))
                                .andExpect(status().isAccepted())
                                .andReturn().getResponse().getContentAsString();
                // Email A + mismatched email B
                String emailMismatchBody = postJson(FORGOT, Map.of("identifier", emailA, "confirmationEmail", emailB))
                                .andExpect(status().isAccepted())
                                .andReturn().getResponse().getContentAsString();

                assertThat(mismatchRegisteredBody).isEqualTo(matchBody);
                assertThat(mismatchUnknownBody).isEqualTo(matchBody);
                assertThat(emailMismatchBody).isEqualTo(matchBody);

                Thread.sleep(300);
                // Zero new emails sent to A, B, or unknownEmail (A has verification + 1 reset;
                // B has verification only).
                assertThat(mail.messagesTo(emailA)).hasSize(2);
                assertThat(mail.messagesTo(emailB)).hasSize(1);
                assertThat(mail.messagesTo(unknownEmail)).isEmpty();

                // Account A's earlier code was NOT invalidated by the mismatches and still
                // completes the reset.
                postJson(RESET, Map.of("identifier", phoneA, "code", codeA, "newPassword", NEW_PASSWORD))
                                .andExpect(status().isNoContent());
                login(emailA, NEW_PASSWORD, false).andExpect(status().isOk());
                login(emailB, PASSWORD, false).andExpect(status().isOk());
        }

        @Test
        void blankOrMalformedConfirmationEmailReturnsValidationError() throws Exception {
                String phone = uniquePhone();
                for (String badEmail : new String[] { "", "   ", "not-an-email", "user@nodot", "a b@example.com" }) {
                        postJson(FORGOT, Map.of("identifier", phone, "confirmationEmail", badEmail))
                                        .andExpect(status().isBadRequest())
                                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                                        .andExpect(jsonPath("$.fieldErrors.confirmationEmail")
                                                        .value("Enter a full email address, like name@example.com"));
                }
        }

        @Test
        void resetEnforcesAccountAndPurposeIsolation() throws Exception {
                String emailA = uniqueEmail();
                String phoneA = uniquePhone();
                String emailB = uniqueEmail();
                String phoneB = uniquePhone();
                registerCustomer(emailA, phoneA);
                registerCustomer(emailB, phoneB);

                // A's PASSWORD_RESET code cannot reset B's password.
                postJson(FORGOT, Map.of("identifier", phoneA, "confirmationEmail", emailA))
                                .andExpect(status().isAccepted());
                String resetCodeA = mail.awaitCode(emailA, RESET_SUBJECT);
                postJson(RESET, Map.of("identifier", phoneB, "code", resetCodeA, "newPassword", NEW_PASSWORD))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
                postJson(RESET, Map.of("identifier", emailB, "code", resetCodeA, "newPassword", NEW_PASSWORD))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
                login(emailB, PASSWORD, false).andExpect(status().isOk());

                // An EMAIL_VERIFICATION code cannot be used on /auth/password/reset.
                String emailC = uniqueEmail();
                String phoneC = uniquePhone();
                register(customerBody(emailC, phoneC)).andExpect(status().isCreated());
                String verificationCodeC = mail.awaitCode(emailC, VERIFICATION_SUBJECT);
                postJson(RESET, Map.of("identifier", phoneC, "code", verificationCodeC, "newPassword", NEW_PASSWORD))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }

        private void backdateCodes(String email, int seconds) {
                jdbc.update("""
                                update identity.verification_codes set created_at = created_at - make_interval(secs => ?)
                                where user_id = (select id from identity.users where email = ?)""",
                                seconds, email);
        }
}
