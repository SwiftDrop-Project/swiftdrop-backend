package com.swiftdrop.notifications.internal.provider;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Turns a failed email into one log line that says why: which SMTP step failed, the SMTP reply code, the exception
 * message, the innermost cause, and (for {@link MailSendException}) each per-message failure from
 * {@code getMessageExceptions()}.
 *
 * <p>Never logs the recipient: every email address in a message is replaced by {@code <address>} (servers often
 * echo it, e.g. "550 5.1.1 &lt;name@example.com&gt;: Recipient address rejected"). Codes and passwords never appear in
 * exception messages: the code is only in the message body, and servers don't echo the password.
 */
final class MailFailures {

    /** The SMTP step (or local stage) that failed. */
    enum Step {
        BUILD_MESSAGE("building the message"),
        CONNECT("connecting to the SMTP server (MAIL_HOST/MAIL_PORT)"),
        TLS("STARTTLS/TLS handshake"),
        AUTH("login (AUTH): MAIL_USERNAME/MAIL_PASSWORD rejected"),
        MAIL_FROM("MAIL FROM: sender address rejected (check MAIL_FROM)"),
        RCPT_TO("RCPT TO: recipient address rejected"),
        DATA("DATA: message rejected"),
        TIMEOUT("waiting for the SMTP server (timeout)"),
        UNKNOWN("sending (step unknown)");

        final String label;

        Step(String label) {
            this.label = label;
        }
    }

    /** An email address, with its angle brackets if it has them ("&lt;a@b.lk&gt;" and "a@b.lk"). */
    private static final Pattern EMAIL = Pattern.compile("<?[^\\s<>\"'(),;:\\[\\]]+@[^\\s<>\"'(),;:\\[\\]]+>?");
    private static final String[] SMTP_VERBS = {"MAIL FROM", "RCPT TO", "AUTH", "STARTTLS", "DATA", "EHLO", "HELO"};
    private static final int MAX_MESSAGE = 300;

    private MailFailures() {
    }

    /**
     * e.g. {@code step="MAIL FROM: sender address rejected (check MAIL_FROM)", smtp=555 [MAIL FROM],
     * error=org.springframework.mail.MailSendException: Failed messages: …, cause=…: 555 5.5.2 Syntax error …,
     * failures=[MAIL_FROM 555 [MAIL FROM] org.eclipse.angus.mail.smtp.SMTPSenderFailedException: …]}.
     */
    static String describe(Throwable failure) {
        List<Throwable> chain = chainOf(failure);
        StringBuilder line = new StringBuilder("step=\"").append(stepOf(failure).label).append('"');
        smtpReply(withMessageFailures(failure)).ifPresent(reply -> line.append(", smtp=").append(reply));
        line.append(", error=").append(summary(failure));
        Throwable innermost = chain.getLast();
        if (innermost != failure) {
            line.append(", cause=").append(summary(innermost));
        }
        if (failure instanceof MailSendException send && send.getMessageExceptions().length > 0) {
            List<String> failures = new ArrayList<>();
            for (Exception perMessage : send.getMessageExceptions()) {
                List<Throwable> perChain = chainOf(perMessage);
                StringBuilder item = new StringBuilder(stepOf(perMessage).name());
                smtpReply(perChain).ifPresent(reply -> item.append(' ').append(reply));
                item.append(' ').append(summary(perChain.getLast()));
                failures.add(item.toString());
            }
            line.append(", failures=").append(failures);
        }
        return line.toString();
    }

    /** The step that failed, looking at the whole cause chain (and, for MailSendException, its message failures). */
    static Step stepOf(Throwable failure) {
        List<Throwable> chain = withMessageFailures(failure);
        for (Throwable t : chain) {
            if (t instanceof MailAuthenticationException || t instanceof AuthenticationFailedException) {
                return Step.AUTH;
            }
        }
        // The SMTP command the server refused (Angus Mail's SMTP*FailedException.getCommand()).
        for (Throwable t : chain) {
            String command = invokeString(t, "getCommand");
            if (command != null) {
                return switch (commandName(command)) {
                    case "MAIL FROM" -> Step.MAIL_FROM;
                    case "RCPT TO" -> Step.RCPT_TO;
                    case "AUTH" -> Step.AUTH;
                    case "STARTTLS" -> Step.TLS;
                    default -> Step.DATA;
                };
            }
        }
        for (Throwable t : chain) {
            if (t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof NoRouteToHostException) {
                return Step.CONNECT;
            }
            if (t instanceof SocketTimeoutException timeout) {
                return String.valueOf(timeout.getMessage()).toLowerCase(Locale.ROOT).contains("connect")
                        ? Step.CONNECT
                        : Step.TIMEOUT;
            }
            if (t instanceof SSLException) {
                return Step.TLS;
            }
            String message = String.valueOf(t.getMessage());
            if (t instanceof MessagingException && message.toUpperCase(Locale.ROOT).contains("STARTTLS")) {
                return Step.TLS;
            }
            if (t instanceof MessagingException && message.contains("Could not connect to SMTP host")) {
                return Step.CONNECT;
            }
        }
        for (Throwable t : chain) {
            if (t instanceof AddressException || t instanceof MailParseException
                    || t instanceof MailPreparationException) {
                return Step.BUILD_MESSAGE;
            }
        }
        return Step.UNKNOWN;
    }

    /** "555 [MAIL FROM]": the SMTP reply code and command, when the server answered with an error. */
    private static Optional<String> smtpReply(List<Throwable> chain) {
        for (Throwable t : chain) {
            if (invoke(t, "getReturnCode") instanceof Integer code && code > 0) {
                String command = invokeString(t, "getCommand");
                return Optional.of(code + (command == null ? "" : " [" + commandName(command) + "]"));
            }
        }
        return Optional.empty();
    }

    /** Only the verb ("MAIL FROM", "RCPT TO", "DATA"): the argument of MAIL/RCPT is an address. */
    private static String commandName(String command) {
        String upper = command.toUpperCase(Locale.ROOT).trim();
        for (String verb : SMTP_VERBS) {
            if (upper.startsWith(verb)) {
                return verb;
            }
        }
        int space = upper.indexOf(' ');
        return space < 0 ? upper : upper.substring(0, space);
    }

    /** "ClassName: message" with email addresses replaced, on one line, at most 300 characters. */
    static String summary(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return t.getClass().getName();
        }
        String clean = EMAIL.matcher(message).replaceAll("<address>").replaceAll("\\s+", " ").trim();
        if (clean.length() > MAX_MESSAGE) {
            clean = clean.substring(0, MAX_MESSAGE) + "...";
        }
        return t.getClass().getName() + ": " + clean;
    }

    /** The cause chain plus, for MailSendException, the chains of its per-message failures (getMessageExceptions). */
    private static List<Throwable> withMessageFailures(Throwable failure) {
        List<Throwable> chain = chainOf(failure);
        if (failure instanceof MailSendException send) {
            for (Exception perMessage : send.getMessageExceptions()) {
                chain.addAll(chainOf(perMessage));
            }
        }
        return chain;
    }

    /** The exception, its causes and MessagingException's next exceptions, without loops. */
    private static List<Throwable> chainOf(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            chain.add(current);
            Throwable next = current.getCause();
            if (next == null && current instanceof MessagingException messaging) {
                next = messaging.getNextException();
            }
            current = next;
        }
        return chain;
    }

    private static String invokeString(Throwable t, String method) {
        return invoke(t, method) instanceof String text ? text : null;
    }

    /** Angus Mail is a runtime-only dependency, so its SMTP exception details are read reflectively. */
    private static Object invoke(Throwable t, String method) {
        try {
            Method getter = t.getClass().getMethod(method);
            return getter.invoke(t);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }
}
