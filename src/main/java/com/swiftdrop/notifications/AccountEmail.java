package com.swiftdrop.notifications;

import java.util.Map;
import java.util.Objects;

/**
 * An account email to send to one person, e.g. a verification code.
 *
 * <p>{@code variables} fill the template's {@code {{name}}} placeholders (the display name is always available as
 * {@code name}). They may contain secrets such as one-time codes, so this record never prints them, and neither does
 * the notifications module: only the type and a masked recipient are ever logged or stored.
 *
 * @param type        which email to send
 * @param recipient   the email address
 * @param displayName how to greet the person, e.g. "Ayesha Fernando"
 * @param variables   template values, e.g. {@code code} and {@code minutes}
 */
public record AccountEmail(Type type, String recipient, String displayName, Map<String, String> variables) {

    public enum Type {
        /** A 6-digit code that confirms the email address after sign-up. Variables: {@code code}, {@code minutes}. */
        EMAIL_VERIFICATION,
        /** A 6-digit code to set a new password. Variables: {@code code}, {@code minutes}. */
        PASSWORD_RESET,
        /** Tells the person their password was just changed. No variables. */
        PASSWORD_CHANGED
    }

    public AccountEmail {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(recipient, "recipient");
        displayName = displayName == null ? "" : displayName;
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    /** Keeps the address and the variables (codes) out of logs. */
    @Override
    public String toString() {
        return "AccountEmail[type=" + type + "]";
    }
}
