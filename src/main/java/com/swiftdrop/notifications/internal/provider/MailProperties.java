package com.swiftdrop.notifications.internal.provider;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Bound from MAIL_FROM. The SMTP connection itself (MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD,
 * MAIL_SMTP_AUTH, MAIL_STARTTLS, MAIL_DEBUG) is Spring Boot's {@code spring.mail.*}.
 *
 * <p>MAIL_FROM may be a plain address ({@code no-reply@swiftdrop.lk}) or include a name
 * ({@code SwiftDrop <team@gmail.com>}). With Gmail the address must be the Gmail account itself. An invalid
 * value stops the backend at startup instead of failing every email later.
 *
 * @param from     MAIL_FROM
 * @param fromName name shown in the inbox when MAIL_FROM has none
 */
@Validated
@ConfigurationProperties("swiftdrop.mail")
public record MailProperties(@NotBlank String from, String fromName) {

    public MailProperties {
        fromName = fromName == null || fromName.isBlank() ? "SwiftDrop" : fromName;
    }

    /**
     * The sender as a parsed address: "SwiftDrop &lt;team@gmail.com&gt;" becomes address {@code team@gmail.com}
     * with name "SwiftDrop". (Passing the whole string as the address makes the SMTP server reject MAIL FROM.)
     */
    public InternetAddress sender() {
        try {
            InternetAddress address = new InternetAddress(from.trim(), true);
            address.validate();
            if (address.getPersonal() == null || address.getPersonal().isBlank()) {
                address.setPersonal(fromName, StandardCharsets.UTF_8.name());
            }
            return address;
        } catch (AddressException | UnsupportedEncodingException ex) {
            throw new IllegalStateException("MAIL_FROM is not a valid email address", ex);
        }
    }

    @AssertTrue(message = "MAIL_FROM must be an email address, like no-reply@swiftdrop.lk or SwiftDrop <team@gmail.com>.")
    public boolean isFromValid() {
        if (from == null || from.isBlank()) {
            return true; // @NotBlank reports it
        }
        try {
            sender();
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }
}
