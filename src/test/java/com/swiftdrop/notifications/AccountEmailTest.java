package com.swiftdrop.notifications;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.notifications.internal.application.EmailMasking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
class AccountEmailTest extends IntegrationTest {

    @Autowired
    NotificationsApi notifications;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void sentEmailIsLoggedWithAMaskedAddressOnly() {
        String email = uniqueAddress();

        notifications.sendAccountEmail(new AccountEmail(AccountEmail.Type.PASSWORD_RESET, email, "Nimal",
                Map.of("code", "135790", "minutes", "10")));

        var message = mail.awaitMessages(email, RESET_SUBJECT, 1).getFirst();
        assertThat(message.text()).contains("Hi Nimal,", "135790", "10 minutes");
        assertThat(message.html()).contains("135790", "#CC4A00");
        Map<String, Object> row = awaitLogRow(email);
        assertThat(row.get("type")).isEqualTo("PASSWORD_RESET");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("error_class")).isNull();
        assertThat(row.get("recipient_masked")).isEqualTo("a•••@" + email.substring(email.indexOf('@') + 1));
        assertThat(row.toString()).doesNotContain("135790").doesNotContain(email);
    }

    @Test
    void failedSendIsLoggedAsFailedWithTheReasonButNotTheAddress(CapturedOutput output) {
        String email = uniqueAddress();
        mail.failSending(true);

        notifications.sendAccountEmail(new AccountEmail(AccountEmail.Type.PASSWORD_CHANGED, email, "Nimal", Map.of()));

        Map<String, Object> row = awaitLogRow(email);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_class")).isEqualTo("org.springframework.mail.MailSendException");
        await().atMost(Duration.ofSeconds(5)).until(() -> output.getAll().contains("Account email PASSWORD_CHANGED failed"));
        String line = output.getAll().lines()
                .filter(l -> l.contains("Account email PASSWORD_CHANGED failed")).reduce((a, b) -> b).orElseThrow();
        assertThat(line)
                .contains("step=\"", "error=org.springframework.mail.MailSendException: simulated SMTP failure")
                .doesNotContain(email);
    }

    @Test
    void nothingIsSentWhenTheCallersTransactionRollsBack() throws Exception {
        String email = uniqueAddress();

        transactions.executeWithoutResult(status -> {
            notifications.sendAccountEmail(new AccountEmail(AccountEmail.Type.EMAIL_VERIFICATION, email, "Nimal",
                    Map.of("code", "246802", "minutes", "10")));
            status.setRollbackOnly();
        });

        Thread.sleep(500);
        assertThat(mail.messagesTo(email)).isEmpty();
        assertThat(logRows(email)).isEmpty();
    }

    @Test
    void htmlValuesAreEscaped() {
        String email = uniqueAddress();

        notifications.sendAccountEmail(new AccountEmail(AccountEmail.Type.PASSWORD_CHANGED, email,
                "<b>Mallory</b>", Map.of()));

        var message = mail.awaitMessages(email, CHANGED_SUBJECT, 1).getFirst();
        assertThat(message.html()).contains("&lt;b&gt;Mallory&lt;/b&gt;").doesNotContain("<b>Mallory</b>");
        assertThat(message.text()).contains("Hi <b>Mallory</b>,");
    }

    @Test
    void masking() {
        assertThat(EmailMasking.mask("ayesha@gmail.com")).isEqualTo("a•••@gmail.com");
        assertThat(EmailMasking.mask("x@y.lk")).isEqualTo("x•••@y.lk");
        assertThat(EmailMasking.mask("no-at-sign")).isEqualTo("n•••");
        assertThat(EmailMasking.mask("")).isEqualTo("•••");
    }

    /** A unique domain, so this test's log row can be told apart from every other test's. */
    private static String uniqueAddress() {
        return "a" + UUID.randomUUID().toString().substring(0, 6) + "@" + UUID.randomUUID() + ".test";
    }

    private Map<String, Object> awaitLogRow(String email) {
        await().atMost(Duration.ofSeconds(10)).until(() -> !logRows(email).isEmpty());
        return logRows(email).getFirst();
    }

    private List<Map<String, Object>> logRows(String email) {
        return jdbc.queryForList("select * from notifications.email_log where recipient_masked = ?",
                EmailMasking.mask(email));
    }
}
