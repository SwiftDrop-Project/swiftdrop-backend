package com.swiftdrop.notifications.internal.provider;

import com.swiftdrop.notifications.AccountEmail;
import com.swiftdrop.notifications.internal.application.AccountEmailRequested;
import com.swiftdrop.notifications.internal.application.EmailMasking;
import com.swiftdrop.notifications.internal.domain.EmailLog;
import com.swiftdrop.notifications.internal.domain.EmailStatus;
import com.swiftdrop.notifications.internal.persistence.EmailLogRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sends account emails over SMTP in a background thread, after the requesting transaction has committed
 * (or straight away when there was no transaction), and records the outcome in {@code notifications.email_log}.
 *
 * <p>Never throws: a mail problem must not affect the request that asked for the email. Logs and the email log
 * contain only the type, a masked address, the status and an exception class name, never the code or the address.
 */
@Component
public class AccountEmailSender {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailSender.class);

    private final JavaMailSender mailSender;
    private final EmailTemplates templates;
    private final MailProperties properties;
    private final EmailLogRepository emailLog;

    public AccountEmailSender(JavaMailSender mailSender, EmailTemplates templates, MailProperties properties,
                              EmailLogRepository emailLog) {
        this.mailSender = mailSender;
        this.templates = templates;
        this.properties = properties;
        this.emailLog = emailLog;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AccountEmailRequested event) {
        AccountEmail email = event.email();
        MimeMessage message;
        try {
            message = build(email);
        } catch (Exception ex) {
            // Template or address problems: nothing was sent to the SMTP server.
            fail(email, ex, "step=\"" + MailFailures.Step.BUILD_MESSAGE.label + "\", error="
                    + MailFailures.summary(ex));
            return;
        }
        try {
            mailSender.send(message);
        } catch (Exception ex) {
            fail(email, ex, MailFailures.describe(ex));
            return;
        }
        UUID logId = record(email, EmailStatus.SENT, null);
        log.info("Account email {} sent (log {})", email.type(), logId);
    }

    private MimeMessage build(AccountEmail email) throws Exception {
        EmailTemplates.Rendered rendered = templates.render(email);
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                StandardCharsets.UTF_8.name());
        // Parsed, so "SwiftDrop <team@gmail.com>" becomes address + name (the raw string is an invalid MAIL FROM).
        helper.setFrom(properties.sender());
        helper.setTo(email.recipient());
        helper.setSubject(rendered.subject());
        helper.setText(rendered.text(), rendered.html());
        return message;
    }

    /** Logs why (never the address, code or password) and records FAILED. */
    private void fail(AccountEmail email, Exception ex, String why) {
        UUID logId = record(email, EmailStatus.FAILED, ex.getClass().getName());
        log.warn("Account email {} failed (log {}): {}", email.type(), logId, why);
    }

    /** The email_log row id, or null if the row could not be written (that is logged too). */
    private UUID record(AccountEmail email, EmailStatus status, String errorClass) {
        try {
            return emailLog.save(new EmailLog(email.type().name(), EmailMasking.mask(email.recipient()), status,
                    errorClass)).getId();
        } catch (RuntimeException ex) {
            log.warn("Account email {} {}: could not write the email log: {}", email.type(), status,
                    ex.getClass().getName());
            return null;
        }
    }
}
