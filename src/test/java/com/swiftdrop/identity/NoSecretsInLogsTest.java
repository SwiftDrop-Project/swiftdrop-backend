package com.swiftdrop.identity;

import com.swiftdrop.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Codes, email addresses and passwords never reach the logs, even across a full verify + reset flow. */
@ExtendWith(OutputCaptureExtension.class)
class NoSecretsInLogsTest extends IntegrationTest {

    @Test
    void codesAndAddressesAreNeverLogged(CapturedOutput output) throws Exception {
        String email = uniqueEmail();
        String phone = uniquePhone();

        register(customerBody(email, phone)).andExpect(status().isCreated());
        String verificationCode = mail.awaitCode(email, VERIFICATION_SUBJECT);
        postJson(VERIFY_PATH, Map.of("email", email, "code", "000000".equals(verificationCode) ? "111111" : "000000"))
                .andExpect(status().isBadRequest());
        postJson(VERIFY_PATH, Map.of("email", email, "code", verificationCode)).andExpect(status().isOk());

        postJson("/api/v1/auth/password/forgot", Map.of("identifier", email)).andExpect(status().isAccepted());
        String resetCode = mail.awaitCode(email, RESET_SUBJECT);
        postJson("/api/v1/auth/password/reset", Map.of("identifier", email, "code", resetCode,
                "newPassword", "afterReset123")).andExpect(status().isNoContent());
        mail.awaitMessages(email, CHANGED_SUBJECT, 1);
        // The background sender logs after each email; wait until all three were logged.
        await().atMost(Duration.ofSeconds(10)).until(() -> output.getAll().contains("Account email PASSWORD_CHANGED sent"));

        String logs = output.getAll();
        assertThat(logs).contains("Account email EMAIL_VERIFICATION sent", "Account email PASSWORD_RESET sent");
        // A code is only "logged" if it appears as its own token (not inside a UUID, container id or timestamp).
        assertThat(logs)
                .doesNotContainPattern(standalone(verificationCode))
                .doesNotContainPattern(standalone(resetCode))
                .doesNotContain(email)
                .doesNotContain(stored(phone))
                .doesNotContain("afterReset123")
                .doesNotContain(PASSWORD);
    }

    private static String standalone(String code) {
        return "(?<![0-9A-Za-z])" + code + "(?![0-9A-Za-z])";
    }
}
