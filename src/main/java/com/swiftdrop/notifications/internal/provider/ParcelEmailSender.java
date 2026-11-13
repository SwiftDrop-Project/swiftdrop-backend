
package com.swiftdrop.notifications.internal.provider;

import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserSummary;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class ParcelEmailSender {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;
    private final IdentityApi identityApi;

    public ParcelEmailSender(
            JavaMailSender mailSender,
            MailProperties mailProperties,
            IdentityApi identityApi) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
        this.identityApi = identityApi;
    }

    public void sendStatusEmail(
            UUID customerId,
            String trackingNumber,
            String parcelStatus) {

        if (customerId == null) {
            throw new IllegalArgumentException(
                    "Customer ID is required");
        }

        UserSummary customer = identityApi.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Customer not found"));

        String recipient = customer.email();

        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException(
                    "Customer email is missing");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();

            MimeMessageHelper helper =
                    new MimeMessageHelper(
                            message,
                            true,
                            StandardCharsets.UTF_8.name());

            helper.setFrom(mailProperties.sender());
            helper.setTo(recipient);
            helper.setSubject("SwiftDrop Parcel Status Update");

            String body =
                    "Hello " + customer.fullName() + ",\n\n" +
                    "Your parcel status has been updated.\n\n" +
                    "Tracking Number: " + trackingNumber + "\n" +
                    "Current Status: " + parcelStatus + "\n\n" +
                    "Thank you for using SwiftDrop.";

            helper.setText(body);

            mailSender.send(message);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to send parcel status email", ex);
        }
    }
}
