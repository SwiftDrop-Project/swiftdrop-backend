package com.swiftdrop.notifications.internal.provider;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The failure line names the SMTP step and the server's answer, and never the addresses. */
class MailFailuresTest {

    private static final String SENDER = "SwiftDrop <team@gmail.com>";
    private static final String RECIPIENT = "ayesha@example.com";

    @Test
    void rejectedSenderIsTheMailFromStep() throws Exception {
        // What Gmail answers when MAIL FROM is "SwiftDrop <team@gmail.com>" sent as a raw address.
        var rejected = new SMTPSenderFailedException(new InternetAddress(SENDER, false),
                "MAIL FROM:<" + SENDER + ">", 555, "555 5.5.2 Syntax error, cannot decode response. For more "
                + "information, go to https://support.google.com/a/answer/3221692 team@gmail.com");
        MailSendException failure = sendFailure(rejected);

        String line = MailFailures.describe(failure);

        assertThat(line)
                .startsWith("step=\"MAIL FROM: sender address rejected (check MAIL_FROM)\"")
                .contains("smtp=555 [MAIL FROM]")
                .contains("failures=[MAIL_FROM 555 [MAIL FROM]")
                .contains("555 5.5.2 Syntax error")
                .contains("SMTPSenderFailedException")
                .doesNotContain("team@gmail.com")
                .doesNotContain(RECIPIENT);
    }

    @Test
    void rejectedRecipientIsTheRcptToStepWithoutTheAddress() throws Exception {
        var rejected = new SMTPAddressFailedException(new InternetAddress(RECIPIENT), "RCPT TO:<" + RECIPIENT + ">",
                550, "550 5.1.1 <" + RECIPIENT + ">: Recipient address rejected: User unknown");

        String line = MailFailures.describe(sendFailure(rejected));

        assertThat(line).contains("step=\"RCPT TO: recipient address rejected\"", "smtp=550 [RCPT TO]",
                "550 5.1.1 <address>: Recipient address rejected").doesNotContain(RECIPIENT);
    }

    @Test
    void wrongPasswordIsTheAuthStep() {
        var failure = new MailAuthenticationException(new AuthenticationFailedException(
                "535-5.7.8 Username and Password not accepted. For more information, go to 535 5.7.8 "
                        + "https://support.google.com/mail/?p=BadCredentials"));

        String line = MailFailures.describe(failure);

        assertThat(line).startsWith("step=\"login (AUTH): MAIL_USERNAME/MAIL_PASSWORD rejected\"")
                .contains("cause=jakarta.mail.AuthenticationFailedException: 535-5.7.8 Username and Password not accepted");
    }

    @Test
    void blockedPortIsTheConnectStepWithTheInnermostCause() {
        var connect = new MessagingException("Couldn't connect to host, port: smtp.gmail.com, 587; timeout 10000",
                new SocketTimeoutException("Connect timed out"));
        var refused = new MessagingException("Could not connect to SMTP host: localhost, port: 1025",
                new ConnectException("Connection refused: getsockopt"));

        assertThat(MailFailures.describe(new MailSendException("Mail server connection failed", connect)))
                .startsWith("step=\"connecting to the SMTP server (MAIL_HOST/MAIL_PORT)\"")
                .contains("cause=java.net.SocketTimeoutException: Connect timed out");
        assertThat(MailFailures.describe(new MailSendException("Mail server connection failed", refused)))
                .startsWith("step=\"connecting")
                .contains("cause=java.net.ConnectException: Connection refused");
    }

    @Test
    void starttlsProblemsAreTheTlsStep() {
        var tls = new MessagingException("STARTTLS is required but host does not support STARTTLS");

        assertThat(MailFailures.describe(new MailSendException("Mail server connection failed", tls)))
                .startsWith("step=\"STARTTLS/TLS handshake\"");
    }

    private static MailSendException sendFailure(Exception perMessage) {
        Map<Object, Exception> failed = new LinkedHashMap<>();
        failed.put(new Object(), perMessage);
        return new MailSendException(failed);
    }
}
