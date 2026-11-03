package com.swiftdrop;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;

/**
 * Replaces the SMTP sender in tests: keeps every email in memory instead of sending it (tests never use real SMTP).
 * {@link #failSending(boolean)} makes every send fail, to test the FAILED path.
 */
public class CapturingMailSender extends JavaMailSenderImpl {

    /** An email as the recipient would see it. */
    public record Captured(String to, String from, String subject, String text, String html) {
    }

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private final List<Captured> messages = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
        if (failing) {
            throw new MailSendException("simulated SMTP failure");
        }
        for (MimeMessage message : mimeMessages) {
            try {
                message.saveChanges();
                String[] parts = new String[2];
                collectBodies(message, parts);
                messages.add(new Captured(addresses(message.getRecipients(Message.RecipientType.TO)),
                        addresses(message.getFrom()), message.getSubject(), parts[0], parts[1]));
            } catch (MessagingException | IOException ex) {
                throw new MailSendException("could not read the test message", ex);
            }
        }
    }

    public void failSending(boolean failing) {
        this.failing = failing;
    }

    public void clear() {
        messages.clear();
    }

    /** Every email sent to {@code email} so far (case-insensitive). */
    public List<Captured> messagesTo(String email) {
        String wanted = email.toLowerCase(Locale.ROOT);
        return new ArrayList<>(messages.stream().filter(m -> m.to().toLowerCase(Locale.ROOT).contains(wanted)).toList());
    }

    /** Waits (emails are sent in the background) until {@code count} emails with this subject reached {@code email}. */
    public List<Captured> awaitMessages(String email, String subject, int count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> withSubject(email, subject).size() >= count);
        return withSubject(email, subject);
    }

    /** The 6-digit code in the newest email with this subject to {@code email} (waits for at least one). */
    public String awaitCode(String email, String subject) {
        List<Captured> found = awaitMessages(email, subject, 1);
        return codeIn(found.getLast());
    }

    public static String codeIn(Captured message) {
        Matcher matcher = CODE.matcher(message.text());
        if (!matcher.find()) {
            throw new AssertionError("No 6-digit code in the email text");
        }
        return matcher.group(1);
    }

    private List<Captured> withSubject(String email, String subject) {
        return messagesTo(email).stream().filter(m -> subject.equals(m.subject())).toList();
    }

    private static void collectBodies(Part part, String[] bodies) throws MessagingException, IOException {
        if (part.isMimeType("text/plain") && bodies[0] == null) {
            bodies[0] = (String) part.getContent();
        } else if (part.isMimeType("text/html") && bodies[1] == null) {
            bodies[1] = (String) part.getContent();
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collectBodies(multipart.getBodyPart(i), bodies);
            }
        }
    }

    private static String addresses(Address[] addresses) {
        return addresses == null ? "" : String.join(",", Arrays.stream(addresses).map(Address::toString).toList());
    }

    /** For assertions that need a single message. */
    public Optional<Captured> last() {
        return messages.isEmpty() ? Optional.empty() : Optional.of(messages.getLast());
    }
}
