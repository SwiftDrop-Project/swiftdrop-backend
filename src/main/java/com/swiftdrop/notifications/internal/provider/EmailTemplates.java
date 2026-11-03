package com.swiftdrop.notifications.internal.provider;

import com.swiftdrop.notifications.AccountEmail;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders account emails from {@code resources/templates/email/<type>.html} and {@code .txt}
 * (e.g. {@code email-verification.html}). Placeholders look like {@code {{code}}}; values are HTML-escaped in the
 * HTML version. Unknown placeholders render as empty text. Templates are read once at startup.
 */
@Component
public class EmailTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]*)\\s*}}");

    private static final Map<AccountEmail.Type, String> SUBJECTS = Map.of(
            AccountEmail.Type.EMAIL_VERIFICATION, "Your SwiftDrop verification code",
            AccountEmail.Type.PASSWORD_RESET, "Your SwiftDrop password reset code",
            AccountEmail.Type.PASSWORD_CHANGED, "Your SwiftDrop password was changed");

    private final Map<AccountEmail.Type, String> html = new EnumMap<>(AccountEmail.Type.class);
    private final Map<AccountEmail.Type, String> text = new EnumMap<>(AccountEmail.Type.class);

    public EmailTemplates() {
        for (AccountEmail.Type type : AccountEmail.Type.values()) {
            String name = type.name().toLowerCase(Locale.ROOT).replace('_', '-');
            html.put(type, read("templates/email/" + name + ".html"));
            text.put(type, read("templates/email/" + name + ".txt"));
        }
    }

    /** A rendered email: subject, plain-text body and HTML body. */
    public record Rendered(String subject, String text, String html) {

        /** The bodies can contain one-time codes. */
        @Override
        public String toString() {
            return "Rendered[subject=" + subject + "]";
        }
    }

    public Rendered render(AccountEmail email) {
        Map<String, String> values = new HashMap<>(email.variables());
        values.putIfAbsent("name", email.displayName().isBlank() ? "there" : email.displayName());
        return new Rendered(SUBJECTS.get(email.type()),
                fill(text.get(email.type()), values, false),
                fill(html.get(email.type()), values, true));
    }

    private static String fill(String template, Map<String, String> values, boolean escapeHtml) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), "");
            if (escapeHtml) {
                value = HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Missing email template " + path, ex);
        }
    }
}
